/*
 * The JASDB software and code is Copyright protected 2011 and owned by Renze de Vries
 * 
 * All the code and design principals in the codebase are also Copyright 2011 
 * protected and owned Renze de Vries. Any unauthorized usage of the code or the 
 * design and principals as in this code is prohibited.
 */
package com.oberasoftware.jasdb.service.local;

import com.oberasoftware.jasdb.engine.StorageService;
import com.oberasoftware.jasdb.engine.StorageServiceFactory;
import com.oberasoftware.jasdb.engine.query.BuilderTransformer;
import com.oberasoftware.jasdb.engine.query.operators.BlockOperation;
import com.oberasoftware.jasdb.core.context.RequestContext;
import com.oberasoftware.jasdb.api.session.query.QueryBuilder;
import com.oberasoftware.jasdb.api.session.query.QueryExecutor;
import com.oberasoftware.jasdb.api.session.query.QueryResult;
import com.oberasoftware.jasdb.api.exceptions.ConfigurationException;
import com.oberasoftware.jasdb.api.exceptions.JasDBStorageException;
import com.oberasoftware.jasdb.api.index.query.SearchLimit;

public class QueryExecutorImpl implements QueryExecutor {
	private QueryBuilder queryBuilder;
	private SearchLimit limit;
	private String bagName;
	private String instanceId;

    private RequestContext requestContext;
	
	public QueryExecutorImpl(RequestContext requestContext, String instanceId, String bagName, QueryBuilder parentBuilder) {
        this.requestContext = requestContext;
		this.queryBuilder = parentBuilder;
		this.bagName = bagName;
		this.instanceId = instanceId;
		this.limit = new SearchLimit();
	}

	@Override
	public QueryExecutor limit(int limit) {
		this.limit = new SearchLimit(limit);
		return this;
	}

	@Override
	public QueryExecutor paging(int start, int max) {
		this.limit = new SearchLimit(start, max);
		return this;
	}

	@Override
	public QueryResult execute() throws JasDBStorageException {
		BlockOperation parentSearchCondition = BuilderTransformer.transformBuilder(queryBuilder);

		try {
			StorageServiceFactory serviceFactory = ApplicationContextProvider.getApplicationContext().getBean(StorageServiceFactory.class);
			StorageService storageService = serviceFactory.getOrCreateStorageService(instanceId, bagName);
			
			return storageService.search(requestContext, parentSearchCondition, limit, queryBuilder.getSortParams());
		} catch(ConfigurationException e) {
			throw new JasDBStorageException("Unable to execute query due to configuration error", e);
		}
		
	}
	

}
