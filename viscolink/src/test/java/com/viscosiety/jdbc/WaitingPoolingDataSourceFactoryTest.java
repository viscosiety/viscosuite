/*
 * Copyright 2026 Viscosiety B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.viscosiety.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WaitingPoolingDataSourceFactoryTest {

    /** Opens the protected createPool for the test; a subclass may call it on itself. */
    private static final class TestFactory extends WaitingPoolingDataSourceFactory {
        DataSource pool(DataSource dataSource, String name) {
            return createPool(dataSource, name);
        }
    }

    private static DataSource h2(String db) {
        // DriverManagerDataSource is not a ConnectionPoolDataSource, so the factory wraps it
        // (H2's own JdbcDataSource would be passed through unwrapped).
        return new DriverManagerDataSource("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test
    void defaultsToThirtySeconds() {
        assertEquals(30, new WaitingPoolingDataSourceFactory().getMaxWait());
    }

    @Test
    void parsesTheSetting() {
        assertEquals(30, WaitingPoolingDataSourceFactory.parseMaxWait(null));
        assertEquals(30, WaitingPoolingDataSourceFactory.parseMaxWait(""));
        assertEquals(30, WaitingPoolingDataSourceFactory.parseMaxWait("  "));
        assertEquals(10, WaitingPoolingDataSourceFactory.parseMaxWait("10"));
        assertEquals(10, WaitingPoolingDataSourceFactory.parseMaxWait(" 10 "));
        assertEquals(0, WaitingPoolingDataSourceFactory.parseMaxWait("0"));
        assertEquals(-1, WaitingPoolingDataSourceFactory.parseMaxWait("-1"));
        assertEquals(30, WaitingPoolingDataSourceFactory.parseMaxWait("thirty"));
        assertEquals(30, WaitingPoolingDataSourceFactory.parseMaxWait("1.5"));
    }

    @Test
    void describesThePool() {
        assertEquals("jdbc/orders: at most 20 connections, a borrow waits at most 30 s",
                WaitingPoolingDataSourceFactory.describePool("jdbc/orders", 20, 30));
        assertEquals("jdbc/orders: at most 20 connections, a borrow waits without limit",
                WaitingPoolingDataSourceFactory.describePool("jdbc/orders", 20, 0));
        assertEquals("jdbc/orders: at most 20 connections, a borrow waits without limit",
                WaitingPoolingDataSourceFactory.describePool("jdbc/orders", 20, -5));
    }

    @Test
    void aBorrowOnAFullPoolFailsAfterTheWait() throws SQLException {
        TestFactory factory = new TestFactory();
        factory.setMaxPoolSize(2);
        factory.setMaxWait(1);
        DataSource pool = factory.pool(h2("waitlimit"), "jdbc/test");
        assertNotSame(DriverManagerDataSource.class, pool.getClass(), "the factory must wrap the datasource");

        try (Connection first = pool.getConnection(); Connection second = pool.getConnection()) {
            long start = System.nanoTime();
            SQLException e = assertThrows(SQLException.class, pool::getConnection);
            long waitedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(e.getMessage().contains("Timeout waiting for idle object"), e.getMessage());
            assertTrue(waitedMs >= 900 && waitedMs < 10_000, "waited " + waitedMs + " ms");
        }
    }

    @Test
    void zeroKeepsWaitingWithoutLimit() throws Exception {
        TestFactory factory = new TestFactory();
        factory.setMaxPoolSize(1);
        factory.setMaxWait(0);
        DataSource pool = factory.pool(h2("nolimit"), "jdbc/test");

        try (Connection held = pool.getConnection()) {
            Thread borrower = new Thread(() -> {
                try (Connection c = pool.getConnection()) {
                    // only reached after the held connection is returned
                } catch (SQLException ignored) {
                    // not expected
                }
            });
            borrower.start();
            borrower.join(1500);
            assertTrue(borrower.isAlive(), "with 0 the borrow must still be waiting after 1.5 s");
            held.close();
            borrower.join(5000);
            assertFalse(borrower.isAlive(), "the borrow must finish once the connection is back");
        }
    }
}
