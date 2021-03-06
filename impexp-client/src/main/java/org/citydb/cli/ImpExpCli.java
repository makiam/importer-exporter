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
package org.citydb.cli;

import org.citydb.ImpExpException;
import org.citydb.citygml.deleter.CityGMLDeleteException;
import org.citydb.citygml.deleter.controller.Deleter;
import org.citydb.citygml.exporter.CityGMLExportException;
import org.citydb.citygml.exporter.controller.Exporter;
import org.citydb.citygml.importer.CityGMLImportException;
import org.citydb.citygml.importer.controller.Importer;
import org.citydb.citygml.validator.ValidationException;
import org.citydb.citygml.validator.controller.Validator;
import org.citydb.config.Config;
import org.citydb.database.DatabaseController;
import org.citydb.database.schema.mapping.SchemaMapping;
import org.citydb.event.EventDispatcher;
import org.citydb.log.Logger;
import org.citydb.modules.kml.controller.KmlExportException;
import org.citydb.modules.kml.controller.KmlExporter;
import org.citydb.registry.ObjectRegistry;
import org.citydb.util.ClientConstants;
import org.citygml4j.builder.jaxb.CityGMLBuilder;

import javax.xml.bind.JAXBContext;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ImpExpCli {
	private final Logger log = Logger.getInstance();
	private final DatabaseController databaseController;
	private final SchemaMapping schemaMapping;
	private CityGMLBuilder cityGMLBuilder;
	private JAXBContext jaxbKmlContext;
	private JAXBContext jaxbColladaContext;
	private Config config;

	public ImpExpCli(JAXBContext jaxbKmlContext,
			JAXBContext jaxbColladaContext,
			Config config) {
		this.jaxbKmlContext = jaxbKmlContext;
		this.jaxbColladaContext = jaxbColladaContext;
		this.config = config;

		databaseController = ObjectRegistry.getInstance().getDatabaseController();
		cityGMLBuilder = ObjectRegistry.getInstance().getCityGMLBuilder();
		schemaMapping = ObjectRegistry.getInstance().getSchemaMapping();
	}

	public boolean doImport(String importFiles) throws ImpExpException {
		// prepare list of files to be validated
		List<Path> files = getFiles(importFiles);
		if (files.size() == 0)
			throw new ImpExpException("Invalid list of files to be imported.");

		if (!databaseController.connect())
			return false;

		log.info("Initializing database import...");

		config.getInternal().setImportFiles(files);
		EventDispatcher eventDispatcher = ObjectRegistry.getInstance().getEventDispatcher();
		Importer importer = new Importer(cityGMLBuilder, schemaMapping, config, eventDispatcher);
		boolean success = false;

		try {
			success = importer.doProcess();
		} catch (CityGMLImportException e) {
			throw new ImpExpException("CityGML import failed due to an internal error.", e);
		} finally {
			try {
				eventDispatcher.flushEvents();
			} catch (InterruptedException e) {
				//
			}

			databaseController.disconnect();
		}

		if (success)
			log.info("Database import successfully finished.");
		else
			log.warn("Database import aborted.");

		return success;
	}

	public boolean doValidate(String validateFiles) throws ImpExpException {
		// prepare list of files to be validated
		List<Path> files = getFiles(validateFiles);
		if (files.size() == 0)
			throw new ImpExpException("Invalid list of files to be validated.");

		log.info("Initializing data validation...");

		config.getInternal().setImportFiles(files);
		EventDispatcher eventDispatcher = ObjectRegistry.getInstance().getEventDispatcher();
		Validator validator = new Validator(config, eventDispatcher);
		boolean success = false;

		try {
			success = validator.doProcess();
		} catch (ValidationException e) {
			throw new ImpExpException("Data validation failed due to an internal error.", e);
		} finally {
			try {
				eventDispatcher.flushEvents();
			} catch (InterruptedException e) {
				//
			}
		}

		if (success)
			log.info("Data validation finished.");
		else
			log.warn("Data validation aborted.");

		return success;
	}

	public boolean doExport(String exportFile) throws ImpExpException {
		setExportFile(exportFile);

		if (!databaseController.connect())
			return false;

		log.info("Initializing database export...");

		EventDispatcher eventDispatcher = ObjectRegistry.getInstance().getEventDispatcher();
		Exporter exporter = new Exporter(cityGMLBuilder, schemaMapping, config, eventDispatcher);
		boolean success = false;

		try {
			success = exporter.doProcess();
		} catch (CityGMLExportException e) {
			throw new ImpExpException("CityGML export failed due to an internal error.", e);
		} finally {
			try {
				eventDispatcher.flushEvents();
			} catch (InterruptedException e) {
				//
			}

			databaseController.disconnect();
		}

		if (success)
			log.info("Database export successfully finished.");
		else
			log.warn("Database export aborted.");

		return success;
	}

	public boolean doDelete() throws ImpExpException {
		if (!databaseController.connect())
			return false;

		log.info("Initializing database delete...");

		EventDispatcher eventDispatcher = ObjectRegistry.getInstance().getEventDispatcher();
		Deleter deleter = new Deleter(config, schemaMapping, eventDispatcher);
		boolean success = false;

		try {
			success = deleter.doProcess();
		} catch (CityGMLDeleteException e) {
			throw new ImpExpException("CityGML delete failed due to an internal error.", e);
		} finally {
			try {
				eventDispatcher.flushEvents();
			} catch (InterruptedException e) {
				//
			}

			databaseController.disconnect();
		}

		if (success)
			log.info("Database delete successfully finished.");
		else
			log.warn("Database delete aborted.");

		return success;
	}
	
	public boolean doKmlExport(String kmlExportFile) throws ImpExpException {
		setExportFile(kmlExportFile);

		if (!databaseController.connect())
			return false;

		log.info("Initializing database export...");

		EventDispatcher eventDispatcher = ObjectRegistry.getInstance().getEventDispatcher();
		KmlExporter kmlExporter = new KmlExporter(jaxbKmlContext, jaxbColladaContext, schemaMapping, config, eventDispatcher);
		boolean success = false;
		
		try {
			success = kmlExporter.doProcess();
		} catch (KmlExportException e) {
			throw new ImpExpException("KML/COLLADA/glTF export failed due to an internal error.", e);
		} finally {
			try {
				eventDispatcher.flushEvents();
			} catch (InterruptedException e) {
				//
			}

			databaseController.disconnect();
		}

		if (success)
			log.info("Database export successfully finished.");
		else
			log.warn("Database export aborted.");

		return success;
	}

	private void setExportFile(String exportFile) throws ImpExpException {
		try {
			config.getInternal().setExportFile(ClientConstants.WORKING_DIR.resolve(exportFile));
		} catch (InvalidPathException e) {
			throw new ImpExpException("'" + exportFile + "' is not a valid file.", e);
		}
	}

	public boolean doTestConnection() throws ImpExpException {
		if (databaseController.connect()) {
			databaseController.disconnect();
			return true;
		} else {
			return false;
		}
	}

	private List<Path> getFiles(String fileNames) {
		List<Path> files = new ArrayList<>();

		for (String part : fileNames.split(";")) {
			if (part == null || part.trim().isEmpty())
				continue;

			File file = new File(part.trim());
			if (file.isDirectory()) {
				files.add(file.toPath());
				continue;
			}

			final String pathName = new File(file.getAbsolutePath()).getParent();
			final String fileName = file.getName().replace("?", ".?").replace("*", ".*?");

			file = new File(pathName);
			if (!file.exists()) {
				log.error("'" + file.toString() + "' does not exist");
				continue;
			}

			File[] wildcardList = file.listFiles((dir, name) -> (name.matches(fileName)));

			if (wildcardList != null && wildcardList.length != 0) {
				for (File item : wildcardList)
					files.add(item.toPath());
			}
		}

		return files;
	}
}
