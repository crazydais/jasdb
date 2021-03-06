/*
 * The JASDB software and code is Copyright protected 2011 and owned by Renze de Vries
 * 
 * All the code and design principals in the codebase are also Copyright 2011 
 * protected and owned Renze de Vries. Any unauthorized usage of the code or the 
 * design and principals as in this code is prohibited.
 */
package com.oberasoftware.jasdb.engine.query;

import com.oberasoftware.jasdb.api.session.Entity;
import com.oberasoftware.jasdb.api.session.query.QueryResult;

import java.util.Collection;
import java.util.Iterator;

/**
 * User: renarj
 * Date: 4/15/12
 * Time: 2:40 PM
 */
public class EntityQueryResult implements QueryResult {
    private Collection<Entity> entities;
    private Iterator<Entity> entityIterator;

    private boolean closed = false;

    public EntityQueryResult(Collection<Entity> entities) {
        this.entities = entities;
        this.entityIterator = entities.iterator();
    }

    @Override
    public long size() {
        return entities.size();
    }

    @Override
    public Iterator<Entity> iterator() {
        return entities.iterator();
    }

    @Override
    public boolean hasNext() {
        return entityIterator.hasNext();
    }

    @Override
    public Entity next() {
        return entityIterator.next();
    }

    @Override
    public void remove() {
        //not implemented
    }

    @Override
    public void close() {
        closed = true;
        entities.clear();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}
