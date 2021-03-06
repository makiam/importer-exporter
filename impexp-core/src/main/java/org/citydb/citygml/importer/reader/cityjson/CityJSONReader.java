package org.citydb.citygml.importer.reader.cityjson;

import org.citydb.citygml.importer.filter.selection.counter.CounterFilter;
import org.citydb.citygml.importer.reader.FeatureReadException;
import org.citydb.citygml.importer.reader.FeatureReader;
import org.citydb.concurrent.WorkerPool;
import org.citydb.event.Event;
import org.citydb.event.EventDispatcher;
import org.citydb.event.EventHandler;
import org.citydb.event.global.EventType;
import org.citydb.file.InputFile;
import org.citydb.registry.ObjectRegistry;
import org.citygml4j.builder.cityjson.json.io.reader.CityJSONInputFactory;
import org.citygml4j.builder.cityjson.json.io.reader.CityJSONReadException;
import org.citygml4j.model.citygml.CityGML;
import org.citygml4j.model.citygml.appearance.Appearance;
import org.citygml4j.model.citygml.core.CityModel;
import org.citygml4j.model.gml.feature.AbstractFeature;
import org.citygml4j.model.gml.feature.FeatureProperty;
import org.citygml4j.xml.io.reader.CityGMLInputFilter;

import java.io.IOException;
import java.util.Iterator;

public class CityJSONReader implements FeatureReader, EventHandler {
    private final CityGMLInputFilter typeFilter;
    private final CounterFilter counterFilter;
    private final CityJSONInputFactory factory;
    private final EventDispatcher eventDispatcher;

    private volatile boolean shouldRun = true;

    CityJSONReader(CityGMLInputFilter typeFilter, CounterFilter counterFilter, CityJSONInputFactory factory) {
        this.typeFilter = typeFilter;
        this.counterFilter = counterFilter;
        this.factory = factory;

        eventDispatcher = ObjectRegistry.getInstance().getEventDispatcher();
        eventDispatcher.addEventHandler(EventType.INTERRUPT,this);
    }

    @Override
    public long getValidationErrors() {
        return 0;
    }

    @Override
    public void read(InputFile inputFile, WorkerPool<CityGML> workerPool) throws FeatureReadException {
        try (org.citygml4j.builder.cityjson.json.io.reader.CityJSONReader reader = factory.createFilteredCityJSONReader(
                factory.createCityJSONReader(inputFile.openStream()), typeFilter)) {
            // read input file into a city model
            CityModel cityModel = reader.read();

            // process city model members
            process(cityModel.getCityObjectMember().iterator(), workerPool);
            process(cityModel.getFeatureMember().iterator(), workerPool);
            process(cityModel.getAppearanceMember().iterator(), workerPool);
        } catch (CityJSONReadException | IOException e) {
            throw new FeatureReadException("Failed to read CityJSON input file.", e);
        }
    }

    private void process(Iterator<? extends FeatureProperty<?>> iter, WorkerPool<CityGML> workerPool) {
        while (shouldRun && iter.hasNext()) {
            AbstractFeature feature = iter.next().getFeature();

            // unset parent to mark the feature as top-level
            feature.unsetParent();

            // remove feature from feature collection
            iter.remove();

            if (feature instanceof CityGML) {
                if (counterFilter != null && !(feature instanceof Appearance)) {
                    if (!counterFilter.isStartIndexSatisfied()) {
                        counterFilter.incrementStartIndex();
                        continue;
                    }

                    counterFilter.incrementCount();
                    if (!counterFilter.isCountSatisfied())
                        continue;
                }

                workerPool.addWork((CityGML) feature);
            }
        }
    }

    @Override
    public void close() throws FeatureReadException {
        eventDispatcher.removeEventHandler(this);
    }

    @Override
    public void handleEvent(Event event) throws Exception {
        shouldRun = false;
    }
}
