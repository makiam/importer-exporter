/*
 * 3D City Database - The Open Source CityGML Database
 * http://www.3dcitydb.org/
 *
 * Copyright 2013 - 2019
 * Chair of Geoinformatics
 * Technical University of Munich, Germany
 * https://www.gis.bgu.tum.de/
 *
 * The 3D City Database is jointly developed with the following
 * cooperation partners:
 *
 * virtualcitySYSTEMS GmbH, Berlin <http://www.virtualcitysystems.de/>
 * M.O.S.S. Computer Grafik Systeme GmbH, Taufkirchen <http://www.moss.de/>
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
package org.citydb.citygml.deleter.controller;

import org.citydb.citygml.deleter.CityGMLDeleteException;
import org.citydb.citygml.deleter.concurrent.DBDeleteWorkerFactory;
import org.citydb.citygml.deleter.database.BundledConnection;
import org.citydb.citygml.deleter.database.DBSplitter;
import org.citydb.citygml.exporter.database.content.DBSplittingResult;
import org.citydb.concurrent.PoolSizeAdaptationStrategy;
import org.citydb.concurrent.WorkerPool;
import org.citydb.config.Config;
import org.citydb.config.internal.Internal;
import org.citydb.config.project.database.Workspace;
import org.citydb.database.adapter.AbstractDatabaseAdapter;
import org.citydb.database.connection.DatabaseConnectionPool;
import org.citydb.database.schema.mapping.SchemaMapping;
import org.citydb.event.Event;
import org.citydb.event.EventDispatcher;
import org.citydb.event.EventHandler;
import org.citydb.event.global.EventType;
import org.citydb.event.global.InterruptEvent;
import org.citydb.event.global.ObjectCounterEvent;
import org.citydb.log.Logger;
import org.citydb.query.Query;
import org.citydb.query.builder.QueryBuildException;
import org.citydb.query.builder.config.ConfigQueryBuilder;
import org.citydb.util.Util;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

public class Deleter implements EventHandler {
	private final Logger log = Logger.getInstance();
	private final SchemaMapping schemaMapping;
	private final EventDispatcher eventDispatcher;
	private final AbstractDatabaseAdapter databaseAdapter;
	private final Config config;
	
	private DBSplitter dbSplitter;
	private volatile boolean shouldRun = true;
	private AtomicBoolean isInterrupted = new AtomicBoolean(false);
	private WorkerPool<DBSplittingResult> dbWorkerPool;
	private Map<Integer, Long> objectCounter;
	private BundledConnection bundledConnection;
	
	public Deleter(Config config, 
			SchemaMapping schemaMapping, 
			EventDispatcher eventDispatcher) {
		this.config = config;
		this.schemaMapping = schemaMapping;
		this.eventDispatcher = eventDispatcher;
		this.databaseAdapter = DatabaseConnectionPool.getInstance().getActiveDatabaseAdapter();
		this.objectCounter = new HashMap<>();
	}

	public void cleanup() {
		eventDispatcher.removeEventHandler(this);
	}

	public boolean doProcess() throws CityGMLDeleteException {
		long start = System.currentTimeMillis();

		// Multithreading may cause DB-Deadlock. It may occur when deleting a CityObjectGroup within
		// one thread, and the cityObjectMembers are being deleted within other threads at the same time.
		// Hence, we use single thread per-default to avoid this issue.
		int minThreads = 1;
		int maxThreads = 1;
		
		// adding listeners
		eventDispatcher.addEventHandler(EventType.OBJECT_COUNTER, this);
		eventDispatcher.addEventHandler(EventType.INTERRUPT, this);

		// checking workspace
		Workspace workspace = config.getProject().getDatabase().getWorkspaces().getDeleteWorkspace();
		if (shouldRun && databaseAdapter.hasVersioningSupport() && 
				!databaseAdapter.getWorkspaceManager().equalsDefaultWorkspaceName(workspace.getName()) &&
				!databaseAdapter.getWorkspaceManager().existsWorkspace(workspace, true))
			return false;
		
		// build query from filter settings
		Query query;
		try {
			ConfigQueryBuilder queryBuilder = new ConfigQueryBuilder(schemaMapping, databaseAdapter);
			if (config.getProject().getDeleter().isUseSimpleQuery())
				query = queryBuilder.buildQuery(config.getProject().getDeleter().getSimpleQuery(), config.getProject().getNamespaceFilter());
			else
				query = queryBuilder.buildQuery(config.getProject().getDeleter().getQuery(), config.getProject().getNamespaceFilter());

		} catch (QueryBuildException e) {
			throw new CityGMLDeleteException("Failed to build the delete query expression.", e);
		}
		
		bundledConnection = new BundledConnection();
		try {				
			dbWorkerPool = new WorkerPool<>(
					"db_deleter_pool",
					minThreads,
					maxThreads,
					PoolSizeAdaptationStrategy.AGGRESSIVE,
					new DBDeleteWorkerFactory(bundledConnection, config, eventDispatcher),
					300,
					false);

			dbWorkerPool.prestartCoreWorkers();
			if (dbWorkerPool.getPoolSize() == 0)
				throw new CityGMLDeleteException("Failed to start database delete worker pool. Check the database connection pool settings.");

			log.info("Deleting city objects from database.");

			// get database splitter and start query
			try {
				dbSplitter = new DBSplitter(schemaMapping, dbWorkerPool, query, config, eventDispatcher);
				if (shouldRun) {
					dbSplitter.setCalculateNumberMatched(Internal.IS_GUI_MODE);
					dbSplitter.startQuery();
				}
			} catch (SQLException | QueryBuildException e) {
				throw new CityGMLDeleteException("Failed to query the database.", e);
			}

			try {
				dbWorkerPool.shutdownAndWait();
			} catch (InterruptedException e) {
				throw new CityGMLDeleteException("Failed to shutdown worker pools.", e);
			}
		} catch (CityGMLDeleteException e) {
			throw e;
		} catch (Throwable e) {
			throw new CityGMLDeleteException("An unexpected error occurred.", e);
		} finally {
			try {
				bundledConnection.close();
			} catch (SQLException e) {
				//
			}
			
			// clean up
			if (dbWorkerPool != null)
				dbWorkerPool.shutdownNow();

			try {
				eventDispatcher.flushEvents();
			} catch (InterruptedException e) {
				//
			}
		}		
		
		// show deleted features
		if (!objectCounter.isEmpty()) {
			log.info("Deleted city objects:");
			Map<String, Long> typeNames = Util.mapObjectCounter(objectCounter, schemaMapping);					
			typeNames.keySet().stream().sorted().forEach(object -> log.info(object + ": " + typeNames.get(object)));			
		}

		if (shouldRun)
			log.info("Process time: " + Util.formatElapsedTime(System.currentTimeMillis() - start) + ".");

		objectCounter.clear();

		return shouldRun;
	}
	
	@Override
	public void handleEvent(Event e) throws Exception {
		if (e.getEventType() == EventType.OBJECT_COUNTER) {
			Map<Integer, Long> counter = ((ObjectCounterEvent)e).getCounter();
			
			for (Entry<Integer, Long> entry : counter.entrySet()) {
				Long tmp = objectCounter.get(entry.getKey());
				objectCounter.put(entry.getKey(), tmp == null ? entry.getValue() : tmp + entry.getValue());
			}
		}

		else if (e.getEventType() == EventType.INTERRUPT) {
			if (isInterrupted.compareAndSet(false, true)) {
				shouldRun = false;
				bundledConnection.setShouldRollback(true);
				InterruptEvent interruptEvent = (InterruptEvent)e;

				if (interruptEvent.getCause() != null) {
					Throwable cause = interruptEvent.getCause();
					if (cause instanceof SQLException) {
						log.error("A SQL error occurred.", cause);
					} else {
						log.error("An error occurred.", cause);
					}
				}

				String msg = interruptEvent.getLogMessage();
				if (msg != null)
					log.log(interruptEvent.getLogLevelType(), msg);

				if (dbSplitter != null)
					dbSplitter.shutdown();

				if (dbWorkerPool != null)
					dbWorkerPool.drainWorkQueue();
			}
		}
	}
	
}
