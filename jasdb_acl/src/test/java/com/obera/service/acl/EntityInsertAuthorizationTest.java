package com.obera.service.acl;

import nl.renarj.jasdb.api.SimpleEntity;
import nl.renarj.jasdb.api.acl.AccessMode;
import nl.renarj.jasdb.api.context.RequestContext;
import nl.renarj.jasdb.core.exceptions.JasDBStorageException;
import nl.renarj.jasdb.service.StorageService;

import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * @author Renze de Vries
 */
public class EntityInsertAuthorizationTest extends AbstractAuthorizationTest {
    public EntityInsertAuthorizationTest() {
        super(AccessMode.READ, AccessMode.WRITE);
    }

    @Override
    protected AuthorizationOperation getOperation() {
        return new AuthorizationOperation() {
            @Override
            public void doOperation(AuthorizationServiceWrapper authorizationServiceWrapper,
                                    StorageService wrappedService, String user, String password) throws JasDBStorageException {
                SimpleEntity entity = new SimpleEntity(UUID.randomUUID().toString());

                authorizationServiceWrapper.insertEntity(createContext(user, password, "localhost"), entity);

                verify(wrappedService, times(1)).insertEntity(any(RequestContext.class), eq(entity));
            }
        };
    }
}
