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

import java.time.Duration;

import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tomcat.dbcp.dbcp2.PoolableConnection;
import org.apache.tomcat.dbcp.dbcp2.PoolableConnectionFactory;
import org.apache.tomcat.dbcp.pool2.impl.GenericObjectPool;

import org.frankframework.jdbc.factory.PoolingDataSourceFactory;
import org.frankframework.util.AppConstants;

/**
 * The Frank!Framework's pool factory with a wait limit: a borrow on a full pool fails after
 * {@code transactionmanager.jdbc.connection.maxWait} seconds (default 30) with DBCP's
 * "Timeout waiting for idle object" error, instead of waiting forever. {@code 0} or lower keeps
 * the framework's behaviour (no limit). Everything else about the pool is the framework's.
 *
 * <p>Swapped in for {@code PoolingDataSourceFactory} by {@link PoolWaitLimitPostProcessor}.
 * Can go once the framework has its own setting (frankframework/frankframework#11967).
 */
public class WaitingPoolingDataSourceFactory extends PoolingDataSourceFactory {

    public static final String MAX_WAIT_PROPERTY = "transactionmanager.jdbc.connection.maxWait";
    public static final int DEFAULT_MAX_WAIT_SECONDS = 30;

    private static final Logger log = LogManager.getLogger(WaitingPoolingDataSourceFactory.class);

    private int maxWait;

    public WaitingPoolingDataSourceFactory() {
        super();
        maxWait = parseMaxWait(AppConstants.getInstance().getProperty(MAX_WAIT_PROPERTY));
    }

    static int parseMaxWait(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_WAIT_SECONDS;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            log.warn("{} [{}] is not a whole number of seconds; using {}", MAX_WAIT_PROPERTY, raw, DEFAULT_MAX_WAIT_SECONDS);
            return DEFAULT_MAX_WAIT_SECONDS;
        }
    }

    static String describePool(String name, int maxPoolSize, int maxWaitSeconds) {
        String wait = maxWaitSeconds > 0
                ? "a borrow waits at most " + maxWaitSeconds + " s"
                : "a borrow waits without limit";
        return name + ": at most " + maxPoolSize + " connections, " + wait;
    }

    public int getMaxWait() {
        return maxWait;
    }

    public void setMaxWait(int seconds) {
        this.maxWait = seconds;
    }

    @Override
    protected DataSource createPool(DataSource dataSource, String dataSourceName) {
        DataSource result = super.createPool(dataSource, dataSourceName);
        if (result != dataSource) {
            log.info(describePool(dataSourceName, maxPoolSize, maxWait));
        }
        return result;
    }

    @Override
    protected GenericObjectPool<PoolableConnection> createConnectionPool(PoolableConnectionFactory poolableConnectionFactory) {
        GenericObjectPool<PoolableConnection> pool = super.createConnectionPool(poolableConnectionFactory);
        if (maxWait > 0) {
            pool.setMaxWait(Duration.ofSeconds(maxWait));
        }
        return pool;
    }
}
