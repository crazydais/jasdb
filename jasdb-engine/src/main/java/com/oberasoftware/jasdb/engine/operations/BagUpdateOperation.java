/*
 * The JASDB software and code is Copyright protected 2011 and owned by Renze de Vries
 * 
 * All the code and design principals in the codebase are also Copyright 2011 
 * protected and owned Renze de Vries. Any unauthorized usage of the code or the 
 * design and principals as in this code is prohibited.
 */
package com.oberasoftware.jasdb.engine.operations;

import com.oberasoftware.jasdb.api.engine.IndexManagerFactory;
import com.oberasoftware.jasdb.api.exceptions.JasDBStorageException;
import com.oberasoftware.jasdb.api.index.Index;
import com.oberasoftware.jasdb.api.index.keys.Key;
import com.oberasoftware.jasdb.api.session.Entity;
import com.oberasoftware.jasdb.api.storage.RecordResult;
import com.oberasoftware.jasdb.api.storage.RecordWriter;
import com.oberasoftware.jasdb.core.index.keys.KeyUtil;
import com.oberasoftware.jasdb.core.index.keys.UUIDKey;
import com.oberasoftware.jasdb.engine.BagOperationUtil;
import com.oberasoftware.jasdb.engine.RecordWriterFactoryLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author Renze de Vries
 */
@Component
@Qualifier("updateOperation")
public class BagUpdateOperation implements DataOperation {
    private static final Logger log = LoggerFactory.getLogger(BagUpdateOperation.class);

    @Autowired
    private RecordWriterFactoryLoader recordWriterFactoryLoader;

    @Autowired
    private IndexManagerFactory indexManagerFactory;

    @Override
    public void doDataOperation(String instanceId, String bag, Entity entity) throws JasDBStorageException {
        UUIDKey documentKey = new UUIDKey(entity.getInternalId());
        RecordWriter<UUIDKey> recordWriter = recordWriterFactoryLoader.loadRecordWriter(instanceId, bag);
        RecordResult result = recordWriter.readRecord(documentKey);

        if(result != null && result.isRecordFound()) {
            Entity oldEntity = BagOperationUtil.toEntity(result.getStream());

            //let's update the actual record, so we can get the new record pointer and set this on the entity
            log.debug("Updating record: {}", entity.getInternalId());
            recordWriter.updateRecord(documentKey, BagOperationUtil.toStream(entity));

            doIndexModifications(instanceId, bag, oldEntity, entity);
        } else {
            throw new JasDBStorageException("Unable to update entity: " + entity.getInternalId() + " record does not exist");
        }
    }

    private void doIndexModifications(String instanceId, String bagName, Entity oldEntity, Entity entity) throws JasDBStorageException {
        log.debug("Starting index update for entity: {}", entity.getInternalId());
        //let's start updating the indexes
        Map<String, Index> indexes = indexManagerFactory.getIndexManager(instanceId).getIndexes(bagName);
        for(Map.Entry<String, Index> indexEntry : indexes.entrySet()) {
            Index index = indexEntry.getValue();

            boolean dataPresentOldEntity = KeyUtil.isAnyDataPresent(oldEntity, index);
            boolean dataPresentNewEntity = KeyUtil.isAnyDataPresent(entity, index);

            Set<Key> keys = BagOperationUtil.createEntityKeys(entity, index);
            if(dataPresentNewEntity && dataPresentOldEntity) {
                //we could have a data update, or just a record pointer update
                Set<Key> oldKeys = BagOperationUtil.createEntityKeys(oldEntity, index);
                Set<Key> removedKeys = new HashSet<>(oldKeys);
                for(Key key : keys) {
                    if(oldKeys.contains(key)) {
                        //the key is still present and not removed
                        removedKeys.remove(key);
                    } else {
                        //not present in index, let's add
                        index.insertIntoIndex(key);
                    }
                }
                
                for(Key removedKey : removedKeys) {
                    //we need to remove from index
                    index.removeFromIndex(removedKey);
                }
            } else if(dataPresentNewEntity) {
                //data was not present yet, so we just do an insert
                BagOperationUtil.doIndexInsert(keys, index);
            } else {
                //data was removed, need to remove from index
                Set<Key> removeKeys = BagOperationUtil.createEntityKeys(oldEntity, index);
                for(Key key : removeKeys) {
                    index.removeFromIndex(key);
                }
            }
        }
        
    }

    public void setRecordWriterFactoryLoader(RecordWriterFactoryLoader recordWriterFactoryLoader) {
        this.recordWriterFactoryLoader = recordWriterFactoryLoader;
    }

    public void setIndexManagerFactory(IndexManagerFactory indexManagerFactory) {
        this.indexManagerFactory = indexManagerFactory;
    }
}
