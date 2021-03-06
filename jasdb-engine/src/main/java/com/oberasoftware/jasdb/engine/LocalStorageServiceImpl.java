package com.oberasoftware.jasdb.engine;

import com.oberasoftware.jasdb.api.exceptions.ConfigurationException;
import com.oberasoftware.jasdb.api.exceptions.JasDBStorageException;
import com.oberasoftware.jasdb.api.exceptions.RuntimeJasDBException;
import com.oberasoftware.jasdb.api.model.IndexDefinition;
import com.oberasoftware.jasdb.api.session.Entity;
import com.oberasoftware.jasdb.api.session.query.QueryResult;
import com.oberasoftware.jasdb.core.SimpleEntity;
import com.oberasoftware.jasdb.core.concurrency.ResourceLockManager;
import com.oberasoftware.jasdb.core.context.RequestContext;
import com.oberasoftware.jasdb.api.engine.IndexManager;
import com.oberasoftware.jasdb.api.engine.IndexManagerFactory;
import com.oberasoftware.jasdb.api.index.Index;
import com.oberasoftware.jasdb.core.index.keys.UUIDKey;
import com.oberasoftware.jasdb.api.index.keys.KeyInfo;
import com.oberasoftware.jasdb.api.index.query.SearchLimit;
import com.oberasoftware.jasdb.api.index.CompositeIndexField;
import com.oberasoftware.jasdb.api.index.IndexField;
import com.oberasoftware.jasdb.api.engine.MetadataStore;
import com.oberasoftware.jasdb.api.session.query.SortParameter;
import com.oberasoftware.jasdb.api.storage.RecordWriter;
import com.oberasoftware.jasdb.core.utils.StringUtils;
import com.oberasoftware.jasdb.api.engine.Configuration;
import com.oberasoftware.jasdb.engine.indexing.IndexScanAndRecovery;
import com.oberasoftware.jasdb.engine.operations.DataOperation;
import com.oberasoftware.jasdb.engine.query.operators.BlockOperation;
import com.oberasoftware.jasdb.engine.search.EntityRetrievalOperation;
import com.oberasoftware.jasdb.engine.search.QuerySearchOperation;
import com.oberasoftware.jasdb.writer.transactional.TransactionalRecordWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component("LocalStorageService")
@Scope("prototype")
public class LocalStorageServiceImpl implements StorageService {
    private static final Logger LOG = LoggerFactory.getLogger(LocalStorageServiceImpl.class);
    private static final String FORCE_REBUILD_COMMAND = "forceRebuild";

    private ExecutorService indexRebuilder = Executors.newFixedThreadPool(INDEX_REBUILD_THREADS);

    private static final int INDEX_REBUILD_THREADS = 2;

    @Autowired
    private RecordWriterFactoryLoader recordWriterFactoryLoader;

    private String instanceId;
    private String bagName;

    @Autowired
    private IdGenerator generator;

    private ResourceLockManager resourceLockManager = new ResourceLockManager();

    @Autowired
    @Qualifier("insertOperation")
    private DataOperation bagInsertOperation;

    @Autowired
    @Qualifier("removeOperation")
    private DataOperation bagRemoveOperation;

    @Autowired
    @Qualifier("updateOperation")
    private DataOperation bagUpdateOperation;

    @Autowired
    @Qualifier("persistOperation")
    private DataOperation bagPersistOperation;

    @Autowired
    private IndexManagerFactory indexManagerFactory;

    @Autowired
    private MetadataStore metadataStore;

    public LocalStorageServiceImpl(String instanceId, String bagName) {
        this.bagName = bagName;
        this.instanceId = instanceId;
    }

    @Override
    public String getBagName() {
        return this.bagName;
    }

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    @Override
	public void closeService() throws JasDBStorageException {
        resourceLockManager.exclusiveLock();
        try {
            closeAndReleaseResources();
        } finally {
            resourceLockManager.exclusiveUnlock(true);
        }
	}

    @Override
    public void flush() throws JasDBStorageException {
        resourceLockManager.exclusiveLock();
        try {
            LOG.debug("Flushing bag data: {}", bagName);
            recordWriterFactoryLoader.loadRecordWriter(instanceId, bagName).flush();
            indexManagerFactory.getIndexManager(instanceId).flush(bagName);
        } finally {
            resourceLockManager.exclusiveUnlock(false);
        }
    }

    private void closeAndReleaseResources() throws JasDBStorageException {
        indexRebuilder.shutdown();
    }

	@Override
	public void  openService(Configuration configuration) throws JasDBStorageException {
        LOG.info("Opening storage service for bag: {}", bagName);

        if(!recordWriterFactoryLoader.loadRecordWriter(instanceId, bagName).isOpen()) {
            throw new ConfigurationException("Unable to open record writer for instance/bag: " + instanceId + '/' + bagName);
        }

        if(!metadataStore.isLastShutdownClean() || Boolean.parseBoolean(System.getProperty(FORCE_REBUILD_COMMAND))) {
            LOG.info("Previous shutdown of: {} was unclean or forced rebuild triggered, scanning and rebuilding indexes", this);
            handleIndexScanAndRebuild();
        }
        LOG.info("Finished opening storage service for bag: {}", bagName);
	}

    @Override
    public void remove() throws JasDBStorageException {
        resourceLockManager.exclusiveLock();
        try {
            indexRebuilder.shutdown();
            recordWriterFactoryLoader.remove(instanceId, bagName);

            IndexManager indexManager = indexManagerFactory.getIndexManager(instanceId);
            Collection<Index> indexes = indexManager.getIndexes(bagName).values();
            for(Index index : indexes) {
                indexManager.removeIndex(bagName, index.getName());
            }
        } finally {
            resourceLockManager.exclusiveUnlock(true);
        }
    }

    private void handleIndexScanAndRebuild() throws JasDBStorageException {
        Collection<Index> indexes = getIndexManager().getIndexes(bagName).values();
        List<Future<?>> indexRebuilds = new ArrayList<>(indexes.size());
        LOG.info("Doing index scan for: {} items", getSize());
        RecordWriter recordWriter = recordWriterFactoryLoader.loadRecordWriter(instanceId, bagName);
        if(recordWriter instanceof TransactionalRecordWriter) {
            TransactionalRecordWriter transactionalRecordWriter = (TransactionalRecordWriter) recordWriter;
            LOG.info("Forcing primary key rebuild first, we need to ensure integrity");
            transactionalRecordWriter.verify(recordResult -> {
                try {
                    return new UUIDKey(BagOperationUtil.toEntity(recordResult.getStream()).getInternalId());
                } catch (JasDBStorageException e) {
                    throw new RuntimeJasDBException("Unable to read jasdb entitiy", e);
                }
            });
        }

        for(final Index index : indexes) {
            LOG.info("Scheduling index rebuild of index: {} for bag: {}", index, bagName);
            indexRebuilds.add(indexRebuilder.submit(new IndexScanAndRecovery(index, getRecordWriter().readAllRecords())));
        }
        for(Future<?> indexRebuild : indexRebuilds) {
            try {
                indexRebuild.get();
            } catch(ExecutionException | InterruptedException e) {
                throw new JasDBStorageException("Unable to initialize bag, index rebuild failed", e);
            }
        }
    }

    @Override
	public void insertEntity(RequestContext context, Entity entity) throws JasDBStorageException {
        resourceLockManager.sharedLock();
        try {
            runIdGeneration(entity);

            bagInsertOperation.doDataOperation(instanceId, bagName, entity);
        } finally {
            resourceLockManager.sharedUnlock();
        }
	}
	
	@Override
	public void removeEntity(RequestContext context, Entity entity) throws JasDBStorageException {
        resourceLockManager.sharedLock();
        try {
            if(StringUtils.stringNotEmpty(entity.getInternalId())) {
                bagRemoveOperation.doDataOperation(instanceId, bagName, entity);
            } else {
                throw new JasDBStorageException("Unable to remove record, entity has no id specified");
            }
        } finally {
            resourceLockManager.sharedUnlock();
        }
	}

    @Override
    public void removeEntity(RequestContext context, String internalId) throws JasDBStorageException {
        removeEntity(context, new SimpleEntity(internalId));
    }

    @Override
	public void updateEntity(RequestContext context, Entity entity) throws JasDBStorageException {
        resourceLockManager.sharedLock();
        try {
            if(StringUtils.stringNotEmpty(entity.getInternalId())) {
                bagUpdateOperation.doDataOperation(instanceId, bagName, entity);
            } else {
                throw new JasDBStorageException("Unable to update record, entity has no id specified");
            }
        } finally {
            resourceLockManager.sharedUnlock();
        }
	}

    @Override
    public void persistEntity(RequestContext context, Entity entity) throws JasDBStorageException {
        resourceLockManager.sharedLock();
        try {
            runIdGeneration(entity);

            bagPersistOperation.doDataOperation(instanceId, bagName, entity);
        } finally {
            resourceLockManager.sharedUnlock();
        }
    }

    private void runIdGeneration(Entity entity) throws JasDBStorageException {
        if(entity.getInternalId() == null || entity.getInternalId().isEmpty()) {
            entity.setInternalId(generator.generateNewId());
        }
    }

    @Override
	public long getSize() throws JasDBStorageException {
		return recordWriterFactoryLoader.loadRecordWriter(instanceId, bagName).getSize();
	}

	@Override
	public long getDiskSize() throws JasDBStorageException {
		return recordWriterFactoryLoader.loadRecordWriter(instanceId, bagName).getDiskSize();
	}

	@Override
	public Entity getEntityById(RequestContext requestContext, String id) throws JasDBStorageException {
        resourceLockManager.sharedLock();
        try {
		    return new EntityRetrievalOperation(getRecordWriter()).getEntityById(id);
        } finally {
            resourceLockManager.sharedUnlock();
        }
	}

	@Override
	public QueryResult getEntities(RequestContext context) throws JasDBStorageException {
        resourceLockManager.sharedLock();
        try {
		    return new EntityRetrievalOperation(getRecordWriter()).getEntities();
        } finally {
            resourceLockManager.sharedUnlock();
        }
	}

	@Override
	public QueryResult getEntities(RequestContext context, int max) throws JasDBStorageException {
        resourceLockManager.sharedLock();
        try {
		    return new EntityRetrievalOperation(getRecordWriter()).getEntities(max);
        } finally {
            resourceLockManager.sharedUnlock();
        }
	}

	@Override
	public QueryResult search(RequestContext context, BlockOperation blockOperation, SearchLimit limit, List<SortParameter> params) throws JasDBStorageException {
        resourceLockManager.sharedLock();
        try {
		    return new QuerySearchOperation(bagName, getIndexManager(), getRecordWriter()).search(blockOperation, limit, params);
        } finally {
            resourceLockManager.sharedUnlock();
        }
	}

    @Override
    public void ensureIndex(IndexField indexField, boolean isUnique, IndexField... valueFields) throws JasDBStorageException {
        resourceLockManager.sharedLock();
        try {
            Index index = getIndexManager().createIndex(bagName, indexField, isUnique, valueFields);
            initializeIndex(index);
        } finally {
            resourceLockManager.sharedUnlock();
        }
    }

    @Override
    public void ensureIndex(CompositeIndexField indexField, boolean isUnique, IndexField... valueFields) throws JasDBStorageException {
        resourceLockManager.sharedLock();
        try {
            Index index = getIndexManager().createIndex(bagName, indexField, isUnique, valueFields);
            initializeIndex(index);
        } finally {
            resourceLockManager.sharedUnlock();
        }
    }

    private void initializeIndex(Index index) throws JasDBStorageException {
        KeyInfo keyInfo = index.getKeyInfo();
        IndexDefinition definition = new IndexDefinition(keyInfo.getKeyName(), keyInfo.keyAsHeader(), keyInfo.valueAsHeader(), index.getIndexType());
        if(metadataStore.containsIndex(instanceId, bagName, definition)) {
            try {
                indexRebuilder.submit(new IndexScanAndRecovery(index, getRecordWriter().readAllRecords(), true)).get();
            } catch(ExecutionException | InterruptedException e) {
                throw new JasDBStorageException("Unable to initialize index, index rebuild failed", e);
            }
        } else {
            throw new JasDBStorageException("Cannot initialize index, does not exist in store");
        }
    }

    @Override
    public List<String> getIndexNames() throws JasDBStorageException {
        Collection<Index> indexes = getIndexManager().getIndexes(bagName).values();
        List<String> indexNames = new ArrayList<>(indexes.size());
        for(Index index : indexes) {
            indexNames.add(index.getName());
        }
        return indexNames;
    }

    @Override
    public void removeIndex(String indexName) throws JasDBStorageException {
        resourceLockManager.sharedLock();
        try {
            IndexManager indexManager = getIndexManager();
            indexManager.removeIndex(bagName, indexName);
        } finally {
            resourceLockManager.sharedUnlock();
        }
    }

    private IndexManager getIndexManager() throws JasDBStorageException {
        return indexManagerFactory.getIndexManager(instanceId);
    }

    private RecordWriter<UUIDKey> getRecordWriter() throws JasDBStorageException {
        return recordWriterFactoryLoader.loadRecordWriter(instanceId, bagName);
    }

    @Override
    public String toString() {
        return "LocalStorageServiceImpl{" +
                "bagName='" + bagName + '\'' +
                ", instanceId='" + instanceId + '\'' +
                '}';
    }
}
