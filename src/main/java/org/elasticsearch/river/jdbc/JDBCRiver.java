/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.river.jdbc;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.XContentBuilder;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverIndexName;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;

public class JDBCRiver extends AbstractRiverComponent implements River {

    private final Client client;
    private final String riverIndexName;
    private final String indexName;
    private final String typeName;
    private final BulkWrite operation;
    private final int bulkSize;
    private final int maxBulkRequests;
    private final TimeValue bulkTimeout;
    private final TimeValue poll;
    private final String url;
    private final String driverClassName;
    private final String username;
    private final String password;
    private final String sql;
    private volatile Thread thread;
    private volatile boolean closed;

    @Inject
    public JDBCRiver(RiverName riverName, RiverSettings settings,
            @RiverIndexName String riverIndexName, Client client) {
        super(riverName, settings);
        this.riverIndexName = riverIndexName;
        this.client = client;
        if (settings.settings().containsKey("jdbc")) {
            Map<String, Object> jdbcSettings = (Map<String, Object>) settings.settings().get("jdbc");
            poll = XContentMapValues.nodeTimeValue(jdbcSettings.get("poll"), TimeValue.timeValueMinutes(60));
            url = XContentMapValues.nodeStringValue(jdbcSettings.get("url"), null);
            driverClassName = XContentMapValues.nodeStringValue(jdbcSettings.get("driver"), null);
            username = XContentMapValues.nodeStringValue(jdbcSettings.get("username"), null);
            password = XContentMapValues.nodeStringValue(jdbcSettings.get("password"), null);
            sql = XContentMapValues.nodeStringValue(jdbcSettings.get("sql"), null);
        } else {
            poll = TimeValue.timeValueMinutes(60);
            url = null;
            driverClassName = null;
            username= null;
            password = null;
            sql = null;
        }
        if (settings.settings().containsKey("index")) {
            Map<String, Object> indexSettings = (Map<String, Object>) settings.settings().get("index");
            indexName = XContentMapValues.nodeStringValue(indexSettings.get("index"), "jdbc");
            typeName = XContentMapValues.nodeStringValue(indexSettings.get("type"), "jdbc");
            bulkSize = XContentMapValues.nodeIntegerValue(indexSettings.get("bulk_size"), 100);
            maxBulkRequests = XContentMapValues.nodeIntegerValue(indexSettings.get("max_bulk_requests"), 30);
            if (indexSettings.containsKey("bulk_timeout")) {
                bulkTimeout = TimeValue.parseTimeValue(XContentMapValues.nodeStringValue(indexSettings.get("bulk_timeout"), "60s"), TimeValue.timeValueMillis(60000));
            } else {
                bulkTimeout = TimeValue.timeValueMillis(60000);
            }
        } else {
            indexName = "jdbc";
            typeName = "jdbc";
            bulkSize = 100;
            maxBulkRequests = 30;
            bulkTimeout = TimeValue.timeValueMillis(60000);
        }
        operation = new BulkWrite(logger, indexName, typeName).setBulkSize(bulkSize).setMaxActiveRequests(maxBulkRequests).setMillisBeforeContinue(bulkTimeout.millis());
    }

    @Override
    public void start() {
        logger.info("starting JDBC connector: URL [{}], driver [{}], sql [{}], indexing to [{}]/[{}], poll [{}]",
                url, driverClassName, sql, indexName, typeName, poll);
        try {
            client.admin().indices().prepareCreate(indexName).execute().actionGet();
        } catch (Exception e) {
            if (ExceptionsHelper.unwrapCause(e) instanceof IndexAlreadyExistsException) {
                // that's fine
            } else if (ExceptionsHelper.unwrapCause(e) instanceof ClusterBlockException) {
                // ok, not recovered yet..., lets start indexing and hope we recover by the first bulk
                // TODO: a smarter logic can be to register for cluster event listener here, and only start sampling when the block is removed...
            } else {
                logger.warn("failed to create index [{}], disabling river...", e, indexName);
                return;
            }
        }
        thread = EsExecutors.daemonThreadFactory(settings.globalSettings(), "JDBC connector").newThread(new JDBCConnector());
        thread.start();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        logger.info("closing JDBC river");
        thread.interrupt();
        closed = true;
    }

    private class JDBCConnector implements Runnable {


        @Override
        public void run() {
            SQLService service = new SQLService();
            while (true) {
                try {
                    Connection connection = service.getConnection(driverClassName, url, username, password);
                    PreparedStatement statement = service.prepareStatement(connection, sql);
                    ResultSet results = service.execute(statement);
                    SQLResultListener listener = new SQLResultListener();
                    listener.setBuilder(jsonBuilder());
                    while (service.nextRow(results, listener)) {
                        operation.write(client, listener.getID(), listener.getBuilder());
                        listener.setBuilder(jsonBuilder());
                        listener.id(null); // reset ID
                    }
                    service.close(results);
                    service.close(statement);
                    service.close(connection);
                    delay("next run");
                } catch (Exception e) {
                    logger.error(e.getMessage(), e, (Object)null);
                    closed = true;
                }
                if (closed) {
                    return;
                }
            }
        }
    }

    private void delay(String reason) {
        if (poll.millis() > 0L) {
            logger.info("{}, waiting {}, URL [{}] driver [{}] sql [{}]",
                    reason, poll, url, driverClassName, sql);
            try {
                Thread.sleep(poll.millis());
            } catch (InterruptedException e1) {
            }
        }
    }
    
    private class SQLResultListener implements ResultListener {

        private XContentBuilder builder;
        private String id;
        
        SQLResultListener() {
        }
        
        public void setBuilder(XContentBuilder builder) {
            this.builder = builder;
        }
        
        public XContentBuilder getBuilder() {
            return builder;
        }
        
        @Override
        public void id(String value) throws IOException {
            this.id = value;
        }
        
        public String getID() {
            return id;
        }
        
        @Override
        public void field(String name, String value) throws IOException {
            builder.field(name, value);
        }

        @Override
        public void field(String name, int value) throws IOException{
            builder.field(name, value);
        }

        @Override
        public void field(String name, long value) throws IOException{
            builder.field(name, value);
        }

        @Override
        public void field(String name, float value) throws IOException{
            builder.field(name, value);
        }

        @Override
        public void field(String name, double value) throws IOException{
            builder.field(name, value);
        }

        @Override
        public void field(String name, boolean value) throws IOException{
            builder.field(name, value);
        }
    }
    
}
