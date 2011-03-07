package de.tub.citydb.concurrent;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.UnmarshallerHandler;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.citygml4j.factory.CityGMLFactory;
import org.citygml4j.model.citygml.core.CityGMLBase;
import org.xml.sax.SAXException;

import de.tub.citydb.concurrent.WorkerPool.WorkQueue;
import de.tub.citydb.config.Config;
import de.tub.citydb.config.project.importer.ImpSchemaType;
import de.tub.citydb.config.project.importer.ImpXMLValidation;
import de.tub.citydb.event.Event;
import de.tub.citydb.event.EventDispatcher;
import de.tub.citydb.event.EventListener;
import de.tub.citydb.event.EventType;
import de.tub.citydb.event.concurrent.InterruptEnum;
import de.tub.citydb.event.concurrent.InterruptEvent;
import de.tub.citydb.event.validation.SchemaLocationEvent;
import de.tub.citydb.jaxb.JAXBValidationEventHandler;
import de.tub.citydb.log.LogLevelType;
import de.tub.citydb.log.Logger;
import de.tub.citydb.sax.events.SAXEvent;

public class FeatureReaderWorker implements Worker<Vector<SAXEvent>> {
	private final Logger LOG = Logger.getInstance();

	// instance members needed for WorkPool
	private volatile boolean shouldRun = true;
	private ReentrantLock runLock = new ReentrantLock();
	private WorkQueue<Vector<SAXEvent>> workQueue = null;
	private Vector<SAXEvent> firstWork;
	private Thread workerThread = null;

	// instance members needed to do work
	private final JAXBContext jaxbContext;
	private final WorkerPool<CityGMLBase> dbWorkerPool;
	private final CityGMLFactory cityGMLFactory;
	private final EventDispatcher eventDispatcher;
	private final Config config;

	// XML validation
	private ImpXMLValidation xmlValidation;
	private FeatureReader featureReader;

	public FeatureReaderWorker(JAXBContext jaxbContext, 
			WorkerPool<CityGMLBase> dbWorkerPool, 
			CityGMLFactory cityGMLFactory,
			EventDispatcher eventDispatcher,
			Config config) {
		this.jaxbContext = jaxbContext;
		this.dbWorkerPool = dbWorkerPool;
		this.cityGMLFactory = cityGMLFactory;
		this.eventDispatcher = eventDispatcher;
		this.config = config;

		init();
	}

	private void init() {
		if (config.getInternal().isUseXMLValidation()) {
			xmlValidation = config.getProject().getImporter().getXMLValidation();	

			featureReader = new ValidatingFeatureReader();
			ValidatingFeatureReader validatingFeatureReader = (ValidatingFeatureReader)featureReader;

			// choose how to obtain schema documents
			if (xmlValidation.getUseLocalSchemas().isSet())
				validatingFeatureReader.handleLocalSchemaLocation();
			else
				eventDispatcher.addListener(EventType.SchemaLocation, validatingFeatureReader);
		} else
			featureReader = new NonValidatingFeatureReader();
	}

	@Override
	public Thread getThread() {
		return workerThread;
	}

	@Override
	public void interrupt() {
		shouldRun = false;
		workerThread.interrupt();
	}

	@Override
	public void interruptIfIdle() {
		final ReentrantLock runLock = this.runLock;
		shouldRun = false;

		if (runLock.tryLock()) {
			try {
				workerThread.interrupt();
			} finally {
				runLock.unlock();
			}
		}
	}

	@Override
	public void setFirstWork(Vector<SAXEvent> firstWork) {
		this.firstWork = firstWork;
	}

	@Override
	public void setThread(Thread workerThread) {
		this.workerThread = workerThread;
	}

	@Override
	public void setWorkQueue(WorkQueue<Vector<SAXEvent>> workQueue) {
		this.workQueue = workQueue;
	}

	@Override
	public void run() {
		if (firstWork != null && shouldRun) {
			doWork(firstWork);
			firstWork = null;
		}

		while (shouldRun) {
			try {
				Vector<SAXEvent> work = workQueue.take();				
				doWork(work);
			} catch (InterruptedException ie) {
				// re-check state
			}
		}
	}

	private void doWork(Vector<SAXEvent> work) {
		final ReentrantLock runLock = this.runLock;
		runLock.lock();

		try{
			featureReader.read(work);
		} finally {
			runLock.unlock();
		}
	}

	private abstract class FeatureReader {
		public abstract void read(Vector<SAXEvent> work);

		protected void forwardResult(JAXBElement<?> featureElem) {
			CityGMLBase cityObject = null;

			if (featureElem.getValue() instanceof org.citygml4j.jaxb.citygml._0_4.AppearancePropertyType) {
				org.citygml4j.jaxb.citygml._0_4.AppearancePropertyType appProp = (org.citygml4j.jaxb.citygml._0_4.AppearancePropertyType)featureElem.getValue();
				if (appProp.isSetAppearance())
					cityObject = new org.citygml4j.impl.jaxb.citygml.appearance._0_4.AppearanceImpl(appProp.getAppearance());
			} else if (featureElem.getValue() instanceof org.citygml4j.jaxb.citygml.app._1.AppearancePropertyType) {
				org.citygml4j.jaxb.citygml.app._1.AppearancePropertyType appProp = (org.citygml4j.jaxb.citygml.app._1.AppearancePropertyType)featureElem.getValue();
				if (appProp.isSetAppearance())
					cityObject = new org.citygml4j.impl.jaxb.citygml.appearance._1.AppearanceImpl(appProp.getAppearance());
			} else			
				cityObject = cityGMLFactory.jaxb2cityGML(featureElem);

			if (cityObject != null)
				dbWorkerPool.addWork(cityObject);
		}
	}

	private final class ValidatingFeatureReader extends FeatureReader implements EventListener {
		private Schema schema;
		private JAXBValidationEventHandler validationEventHandler;
		private boolean forwardResult;

		private ValidatingFeatureReader() {
			validationEventHandler = new JAXBValidationEventHandler(eventDispatcher, !xmlValidation.isSetReportOneErrorPerFeature());
			forwardResult = (dbWorkerPool != null && cityGMLFactory != null);
		}

		@Override
		public void read(Vector<SAXEvent> work) {
			if (schema == null)
				return;

			try{
				Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
				unmarshaller.setSchema(schema);

				validationEventHandler.reset();
				unmarshaller.setEventHandler(validationEventHandler);

				UnmarshallerHandler unmarshallerHandler = unmarshaller.getUnmarshallerHandler();

				for (SAXEvent saxEvent : work) {
					if (saxEvent.getDocumentLocation() != null) {
						validationEventHandler.setLineNumber(saxEvent.getDocumentLocation().getLineNumber());
						validationEventHandler.setColumnNumber(saxEvent.getDocumentLocation().getColumnNumber());
					}

					saxEvent.send(unmarshallerHandler);
				}

				JAXBElement<?> featureElem = (JAXBElement<?>)unmarshallerHandler.getResult();
				if (featureElem == null || featureElem.getValue() == null)
					return;

				if (forwardResult && !validationEventHandler.hasEvents())
					forwardResult(featureElem);

			} catch (JAXBException jaxbE) {
				LOG.error(jaxbE.getMessage());
			} catch (SAXException saxE) {
				//
			}
		}

		private void handleLocalSchemaLocation() {
			Set<ImpSchemaType> schemas = xmlValidation.getUseLocalSchemas().getSchemas();
			List<String> schemaLocations = new ArrayList<String>();

			for (ImpSchemaType schema : schemas) {
				if (schema != null) {
					switch (schema) {
					case CityGML_v1_0_0:
						schemaLocations.add("schemas/CityGML/1.0.0/baseProfile.xsd");
						break;
					case CityGML_v0_4_0:
						schemaLocations.add("schemas/CityGML/0.4.0/CityGML.xsd");
						break;
					}
				}
			}

			if (!schemaLocations.isEmpty()) {
				Source[] sources =  new Source[schemaLocations.size()];
				int i = 0;

				for (String schemaLocation : schemaLocations)
					sources[i++] = new StreamSource(new File(schemaLocation));

				initSchema(sources);
			}	
		}

		@Override
		public void handleEvent(Event e) throws Exception {
			if (e.getEventType() == EventType.SchemaLocation) {
				Set<URL> schemaLocationURLs = ((SchemaLocationEvent)e).getSchemaLocationURLs();

				Source[] sources =  new Source[schemaLocationURLs.size()];
				int i = 0;

				for (URL schemaLocationURL : schemaLocationURLs)
					sources[i++] = new StreamSource(schemaLocationURL.toString());

				initSchema(sources);
			}
		}

		private void initSchema(Source[] sources) {
			try {
				SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
				schema = schemaFactory.newSchema(sources);
			} catch (SAXException saxE) {
				eventDispatcher.triggerEvent(new InterruptEvent(InterruptEnum.READ_SCHEMA_ERROR, 
						"XML error: " + saxE.getMessage(), 
						LogLevelType.ERROR));
			}	
		}

	}

	private final class NonValidatingFeatureReader extends FeatureReader {

		@Override
		public void read(Vector<SAXEvent> work) {		
			try{
				Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
				UnmarshallerHandler unmarshallerHandler = unmarshaller.getUnmarshallerHandler();

				for (SAXEvent saxEvent : work)
					saxEvent.send(unmarshallerHandler);

				JAXBElement<?> featureElem = (JAXBElement<?>)unmarshallerHandler.getResult();
				if (featureElem == null || featureElem.getValue() == null)
					return;

				forwardResult(featureElem);

			} catch (JAXBException jaxbE) {
				LOG.error(jaxbE.getMessage());
			} catch (SAXException saxE) {
				LOG.error("XML error: " + saxE.getMessage());
			}
		}		
	}
}
