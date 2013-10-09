package com.obera.jasdb.local;

import nl.renarj.jasdb.service.DBSessionTest;

/**
 * @author Renze de Vries
 */
public class SecureLocalDBSessionTest extends DBSessionTest {
    public SecureLocalDBSessionTest() {
        super(new OverrideSecureSessionFactory());
    }

    @Override
    public void tearDown() throws Exception {
        System.setProperty("jasdb-config", "");
        super.tearDown();
    }

    @Override
    public void setUp() throws Exception {
        System.setProperty("jasdb-config", "jasdb-local-withsecurity.xml");
        super.setUp();
    }
}
