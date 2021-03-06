/*******************************************************************************
 * * Copyright 2013 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.client.couchdb;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpStatus;
import org.apache.http.RequestLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.scheme.SchemeSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.impetus.client.couchdb.CouchDBDesignDocument.MapReduce;
import com.impetus.kundera.KunderaException;
import com.impetus.kundera.configure.schema.IndexInfo;
import com.impetus.kundera.configure.schema.SchemaGenerationException;
import com.impetus.kundera.configure.schema.TableInfo;
import com.impetus.kundera.configure.schema.api.AbstractSchemaManager;
import com.impetus.kundera.configure.schema.api.SchemaManager;
import com.impetus.kundera.persistence.EntityManagerFactoryImpl.KunderaMetadata;

/**
 * Schema Manager for couchdb.
 * 
 * @author Kuldeep.Mishra
 * 
 */
public class CouchDBSchemaManager extends AbstractSchemaManager implements SchemaManager
{
    /** The logger. */
    private static Logger logger = LoggerFactory.getLogger(CouchDBSchemaManager.class);

    private HttpClient httpClient;

    private HttpHost httpHost;

    private Gson gson = new Gson();

    public CouchDBSchemaManager(String clientFactory, Map<String, Object> externalProperties, final KunderaMetadata kunderaMetadata)
    {
        super(clientFactory, externalProperties, kunderaMetadata);
    }

    @Override
    /**
     * Export schema handles the handleOperation method.
     */
    public void exportSchema(final String persistenceUnit, List<TableInfo> schemas)
    {
        super.exportSchema(persistenceUnit, schemas);
    }

    /**
     * drop design document from db.
     */
    @Override
    public void dropSchema()
    {
        try
        {
            for (TableInfo tableInfo : tableInfos)
            {
                HttpResponse deleteResponse = null;
                Map<String, MapReduce> views = new HashMap<String, MapReduce>();
                String id = CouchDBConstants.DESIGN + tableInfo.getTableName();
                CouchDBDesignDocument designDocument = getDesignDocument(id);
                designDocument.setLanguage(CouchDBConstants.LANGUAGE);
                views = designDocument.getViews();
                if (views != null)
                {
                    StringBuilder builder = new StringBuilder("rev=");
                    builder.append(designDocument.get_rev());
                    URI uri = new URI(CouchDBConstants.PROTOCOL, null, httpHost.getHostName(), httpHost.getPort(),
                            CouchDBConstants.URL_SAPRATOR + databaseName.toLowerCase() + CouchDBConstants.URL_SAPRATOR
                                    + id, builder.toString(), null);
                    HttpDelete delete = new HttpDelete(uri);
                    try
                    {
                        deleteResponse = httpClient.execute(httpHost, delete, CouchDBUtils.getContext(httpHost));
                    }
                    finally
                    {
                        CouchDBUtils.closeContent(deleteResponse);
                    }
                }
            }
        }
        catch (Exception e)
        {
            logger.error("Error while creating database, caused by {}.", e);
            throw new SchemaGenerationException("Error while creating database", e, "couchDB");
        }
    }

    /**
     * To validate entity.
     */
    @Override
    public boolean validateEntity(Class clazz)
    {
        return true;
    }

    /**
     * Instantiate http client.
     */
    @Override
    protected boolean initiateClient()
    {
        try
        {
            SchemeSocketFactory ssf = null;
            ssf = PlainSocketFactory.getSocketFactory();
            SchemeRegistry schemeRegistry = new SchemeRegistry();
            schemeRegistry.register(new Scheme("http", Integer.parseInt(port), ssf));
            PoolingClientConnectionManager ccm = new PoolingClientConnectionManager(schemeRegistry);
            httpClient = new DefaultHttpClient(ccm);
            httpHost = new HttpHost(hosts[0], Integer.parseInt(port), "http");
            // Http params
            httpClient.getParams().setParameter(CoreProtocolPNames.HTTP_CONTENT_CHARSET, "UTF-8");

            // basic authentication
            if (userName != null && password != null)
            {
                ((AbstractHttpClient) httpClient).getCredentialsProvider().setCredentials(
                        new AuthScope(hosts[0], Integer.parseInt(port)),
                        new UsernamePasswordCredentials(userName, password));
            }
            // request interceptor
            ((DefaultHttpClient) httpClient).addRequestInterceptor(new HttpRequestInterceptor()
            {
                public void process(final HttpRequest request, final HttpContext context) throws IOException
                {
                    if (logger.isInfoEnabled())
                    {
                        RequestLine requestLine = request.getRequestLine();
                        logger.info(">> " + requestLine.getMethod() + " " + URI.create(requestLine.getUri()).getPath());
                    }
                }
            });
            // response interceptor
            ((DefaultHttpClient) httpClient).addResponseInterceptor(new HttpResponseInterceptor()
            {
                public void process(final HttpResponse response, final HttpContext context) throws IOException
                {
                    if (logger.isInfoEnabled())
                        logger.info("<< Status: " + response.getStatusLine().getStatusCode());
                }
            });
        }
        catch (Exception e)
        {
            logger.error("Error Creating HTTP client {}. ", e);
            throw new IllegalStateException(e);
        }
        return true;

    }

    /**
     * Validate design document.
     */
    @Override
    protected void validate(List<TableInfo> tableInfos)
    {
        try
        {
            if (!checkForDBExistence())
            {
                throw new SchemaGenerationException("Database " + databaseName + " not exist");
            }

            for (TableInfo tableInfo : tableInfos)
            {
                Map<String, MapReduce> views = new HashMap<String, MapReduce>();
                String id = CouchDBConstants.DESIGN + tableInfo.getTableName();
                CouchDBDesignDocument designDocument = getDesignDocument(id);
                designDocument.setLanguage(CouchDBConstants.LANGUAGE);
                views = designDocument.getViews();
                if (views == null)
                {
                    logger.warn("No view exist for table " + tableInfo.getTableName()
                            + "so any query will not produce any result");
                    return;
                }
                for (IndexInfo indexInfo : tableInfo.getColumnsToBeIndexed())
                {
                    if (views.get(indexInfo.getColumnName()) == null)
                    {
                        logger.warn("No view exist for column " + indexInfo.getColumnName() + " of table "
                                + tableInfo.getTableName() + "so any query on column " + indexInfo.getColumnName()
                                + "will not produce any result");
                    }
                }

                // for id column.
                if (views.get(tableInfo.getIdColumnName()) == null)
                {
                    logger.warn("No view exist for id column " + tableInfo.getIdColumnName() + " of table "
                            + tableInfo.getTableName() + "so any query on id column " + tableInfo.getIdColumnName()
                            + "will not produce any result");
                }

                // for select all.
                if (views.get("all") == null)
                {
                    logger.warn("No view exist for select all for table " + tableInfo.getTableName()
                            + "so select all query will not produce any result");
                }
            }
        }
        catch (Exception e)
        {
            throw new SchemaGenerationException("Database " + databaseName + " not exist");
        }
    }

    /**
     * Update design document.
     */
    @Override
    protected void update(List<TableInfo> tableInfos)
    {
        try
        {
            HttpResponse response = null;

            createDatabaseIfNotExist(false);

            for (TableInfo tableInfo : tableInfos)
            {
                String id = CouchDBConstants.DESIGN + tableInfo.getTableName();
                CouchDBDesignDocument designDocument = getDesignDocument(id);
                designDocument.setLanguage(CouchDBConstants.LANGUAGE);
                Map<String, MapReduce> views = designDocument.getViews();
                if (views == null)
                {
                    views = new HashMap<String, MapReduce>();
                }
                for (IndexInfo indexInfo : tableInfo.getColumnsToBeIndexed())
                {
                    createViewIfNotExist(views, indexInfo.getColumnName());
                }

                // for id column.
                createViewIfNotExist(views, tableInfo.getIdColumnName());

                // for select all.
                createViewForSelectAllIfNotExist(tableInfo, views);

                designDocument.setViews(views);

                URI uri = null;
                if (designDocument.get_rev() == null)
                {
                    uri = new URI(CouchDBConstants.PROTOCOL, null, httpHost.getHostName(), httpHost.getPort(),
                            CouchDBConstants.URL_SAPRATOR + databaseName.toLowerCase() + CouchDBConstants.URL_SAPRATOR
                                    + id, null, null);
                }
                else
                {
                    StringBuilder builder = new StringBuilder("rev=");
                    builder.append(designDocument.get_rev());
                    uri = new URI(CouchDBConstants.PROTOCOL, null, httpHost.getHostName(), httpHost.getPort(),
                            CouchDBConstants.URL_SAPRATOR + databaseName.toLowerCase() + CouchDBConstants.URL_SAPRATOR
                                    + id, builder.toString(), null);
                }
                HttpPut put = new HttpPut(uri);

                String jsonObject = gson.toJson(designDocument);
                StringEntity entity = new StringEntity(jsonObject);
                put.setEntity(entity);
                try
                {
                    response = httpClient.execute(httpHost, put, CouchDBUtils.getContext(httpHost));
                }
                finally
                {
                    CouchDBUtils.closeContent(response);
                }
            }
        }
        catch (Exception e)
        {
            logger.error("Error while creating database, caused by {}.", e);
            throw new SchemaGenerationException("Error while creating database", e, "couchDB");
        }
    }

    /**
     * Create database and design document.
     */
    @Override
    protected void create(List<TableInfo> tableInfos)
    {
        try
        {
            HttpResponse response = null;
            createDatabaseIfNotExist(true);
            for (TableInfo tableInfo : tableInfos)
            {
                CouchDBDesignDocument designDocument = new CouchDBDesignDocument();
                Map<String, MapReduce> views = new HashMap<String, CouchDBDesignDocument.MapReduce>();
                designDocument.setLanguage(CouchDBConstants.LANGUAGE);
                String id = CouchDBConstants.DESIGN + tableInfo.getTableName();
                for (IndexInfo indexInfo : tableInfo.getColumnsToBeIndexed())
                {
                    createView(views, indexInfo.getColumnName());
                }

                // for id column.
                createView(views, tableInfo.getIdColumnName());

                // for select all.
                createViewForSelectAll(tableInfo, views);

                designDocument.setViews(views);
                URI uri = new URI(
                        CouchDBConstants.PROTOCOL,
                        null,
                        httpHost.getHostName(),
                        httpHost.getPort(),
                        CouchDBConstants.URL_SAPRATOR + databaseName.toLowerCase() + CouchDBConstants.URL_SAPRATOR + id,
                        null, null);
                HttpPut put = new HttpPut(uri);

                String jsonObject = gson.toJson(designDocument);
                StringEntity entity = new StringEntity(jsonObject);
                put.setEntity(entity);
                try
                {
                    response = httpClient.execute(httpHost, put, CouchDBUtils.getContext(httpHost));
                }
                finally
                {
                    CouchDBUtils.closeContent(response);
                }
            }
        }
        catch (Exception e)
        {
            logger.error("Error while creating database {} , caused by {}.", databaseName, e);
            throw new SchemaGenerationException("Error while creating database", e, "couchDB");
        }
    }

    /**
     * Create database and design document.
     */
    @Override
    protected void create_drop(List<TableInfo> tableInfos)
    {
        create(tableInfos);
    }

    /**
     * 
     * @param views
     * @param columnName
     */
    private void createViewIfNotExist(Map<String, MapReduce> views, String columnName)
    {
        if (views.get(columnName) == null)
        {
            createView(views, columnName);
        }
    }

    /**
     * 
     * @param views
     * @param columnName
     */
    private void createView(Map<String, MapReduce> views, String columnName)
    {
        MapReduce mapr = new MapReduce();
        mapr.setMap("function(doc){if(doc." + columnName + "){emit(doc." + columnName + ", doc);}}");
        views.put(columnName, mapr);
    }

    /**
     * 
     * @param tableInfo
     * @param views
     */
    private void createViewForSelectAllIfNotExist(TableInfo tableInfo, Map<String, MapReduce> views)
    {
        if (views.get("all") == null)
        {
            createViewForSelectAll(tableInfo, views);
        }
    }

    /**
     * 
     * @param tableInfo
     * @param views
     */
    private void createViewForSelectAll(TableInfo tableInfo, Map<String, MapReduce> views)
    {
        MapReduce mapr = new MapReduce();
        mapr.setMap("function(doc){if(doc." + tableInfo.getIdColumnName() + "){emit(null, doc);}}");
        views.put("all", mapr);
    }

    /**
     * 
     * @param drop
     * @throws URISyntaxException
     * @throws IOException
     * @throws ClientProtocolException
     */
    private void createDatabaseIfNotExist(boolean drop) throws URISyntaxException, IOException, ClientProtocolException
    {
        boolean exist = checkForDBExistence();

        if (exist && drop)
        {
            dropDatabase();
            exist = false;
        }
        if (!exist)
        {
            URI uri = new URI(CouchDBConstants.PROTOCOL, null, httpHost.getHostName(), httpHost.getPort(),
                    CouchDBConstants.URL_SAPRATOR + databaseName.toLowerCase(), null, null);

            HttpPut put = new HttpPut(uri);
            HttpResponse putRes = null;
            try
            {
                // creating database.
                putRes = httpClient.execute(httpHost, put, CouchDBUtils.getContext(httpHost));
            }
            finally
            {
                CouchDBUtils.closeContent(putRes);
            }
        }
    }

    /**
     * Check for db existence.
     * 
     * @return
     * @throws ClientProtocolException
     * @throws IOException
     * @throws URISyntaxException
     */
    private boolean checkForDBExistence() throws ClientProtocolException, IOException, URISyntaxException
    {
        URI uri = new URI(CouchDBConstants.PROTOCOL, null, httpHost.getHostName(), httpHost.getPort(),
                CouchDBConstants.URL_SAPRATOR + databaseName.toLowerCase(), null, null);

        HttpGet get = new HttpGet(uri);
        HttpResponse getRes = null;
        try
        {
            // creating database.
            getRes = httpClient.execute(httpHost, get, CouchDBUtils.getContext(httpHost));
            if (getRes.getStatusLine().getStatusCode() == HttpStatus.SC_OK)
            {
                return true;
            }
            return false;
        }
        finally
        {
            CouchDBUtils.closeContent(getRes);
        }
    }

    /**
     * Drop database.
     * 
     * @throws IOException
     * @throws ClientProtocolException
     * @throws URISyntaxException
     */
    private void dropDatabase() throws IOException, ClientProtocolException, URISyntaxException
    {
        HttpResponse delRes = null;
        try
        {
            URI uri = new URI(CouchDBConstants.PROTOCOL, null, httpHost.getHostName(), httpHost.getPort(),
                    CouchDBConstants.URL_SAPRATOR + databaseName.toLowerCase(), null, null);
            HttpDelete delete = new HttpDelete(uri);
            delRes = httpClient.execute(httpHost, delete, CouchDBUtils.getContext(httpHost));
        }
        finally
        {
            CouchDBUtils.closeContent(delRes);
        }
    }

    /**
     * Get design document.
     * 
     * @param id
     * @return
     */
    private CouchDBDesignDocument getDesignDocument(String id)
    {
        HttpResponse response = null;
        try
        {
            URI uri = new URI(CouchDBConstants.PROTOCOL, null, httpHost.getHostName(), httpHost.getPort(),
                    CouchDBConstants.URL_SAPRATOR + databaseName.toLowerCase() + CouchDBConstants.URL_SAPRATOR + id,
                    null, null);
            HttpGet get = new HttpGet(uri);
            get.addHeader("Accept", "application/json");
            response = httpClient.execute(httpHost, get, CouchDBUtils.getContext(httpHost));

            InputStream content = response.getEntity().getContent();
            Reader reader = new InputStreamReader(content);

            JsonObject jsonObject = gson.fromJson(reader, JsonObject.class);
            return gson.fromJson(jsonObject, CouchDBDesignDocument.class);
        }
        catch (Exception e)
        {
            logger.error("Error while deleting object, Caused by: .", e);
            throw new KunderaException(e);
        }
        finally
        {
            CouchDBUtils.closeContent(response);
        }
    }
}