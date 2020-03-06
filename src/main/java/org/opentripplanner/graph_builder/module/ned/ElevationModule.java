package org.opentripplanner.graph_builder.module.ned;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.Interpolator2D;
import org.geotools.geometry.DirectPosition2D;
import org.geotools.referencing.CRS;
import org.opengis.coverage.Coverage;
import org.opengis.coverage.PointOutsideCoverageException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.common.geometry.PackedCoordinateSequence;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.common.pqueue.BinHeap;
import org.opentripplanner.graph_builder.annotation.ElevationFlattened;
import org.opentripplanner.graph_builder.annotation.Graphwide;
import org.opentripplanner.graph_builder.module.GraphBuilderModuleSummary;
import org.opentripplanner.graph_builder.module.GraphBuilderTaskSummary;
import org.opentripplanner.graph_builder.services.GraphBuilderModule;
import org.opentripplanner.graph_builder.services.ned.ElevationGridCoverageFactory;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.edgetype.StreetWithElevationEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.util.PolylineEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.media.jai.InterpolationBilinear;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link org.opentripplanner.graph_builder.services.GraphBuilderModule} plugin that applies elevation data to street data that has already
 * been loaded into a (@link Graph}, creating elevation profiles for each Street encountered
 * in the Graph. Depending on the {@link ElevationGridCoverageFactory} specified
 * this could be auto-downloaded and cached National Elevation Dataset (NED) raster data or
 * a GeoTIFF file. The elevation profiles are stored as {@link PackedCoordinateSequence} objects,
 * where each (x,y) pair represents one sample, with the x-coord representing the distance along
 * the edge measured from the start, and the y-coord representing the sampled elevation at that
 * point (both in meters).
 */
public class ElevationModule implements GraphBuilderModule {

    private static final Logger log = LoggerFactory.getLogger(ElevationModule.class);

    private final ElevationGridCoverageFactory gridCoverageFactory;
    private final boolean readCachedElevations;
    private final boolean writeCachedElevations;
    private final File cachedElevationsFile;

    private HashMap<String, PackedCoordinateSequence> cachedElevations;

    private Coverage coverage;

    // Keep track of the proportion of elevation fetch operations that fail so we can issue warnings. AtomicInteger is
    // used to provide thread-safe updating capabilities.
    private final AtomicInteger nPointsEvaluated = new AtomicInteger(0);
    private final AtomicInteger nPointsOutsideDEM = new AtomicInteger(0);

    /**
     * The distance between samples in meters. Defaults to 10m, the approximate resolution of 1/3
     * arc-second NED data.
     */
    private double distanceBetweenSamplesM = 10;

    /** used to transform street coordinates into the projection used by the elevation data */
    private MathTransform transformer;

    // used only for testing purposes
    public ElevationModule(ElevationGridCoverageFactory factory) {
        gridCoverageFactory = factory;
        cachedElevationsFile = null;
        readCachedElevations = false;
        writeCachedElevations = false;
    }

    public ElevationModule(
        ElevationGridCoverageFactory factory,
        File cacheDirectory,
        boolean readCachedElevations,
        boolean writeCachedElevations
    ) {
        gridCoverageFactory = factory;
        cachedElevationsFile = new File(cacheDirectory, "cached_elevations.obj");
        this.readCachedElevations = readCachedElevations;
        this.writeCachedElevations = writeCachedElevations;
    }

    public List<String> provides() {
        return Arrays.asList("elevation");
    }

    public List<String> getPrerequisites() {
        return Arrays.asList("streets");
    }

    @Override
    public void buildGraph(Graph graph, GraphBuilderModuleSummary graphBuilderModuleSummary) {
        GraphBuilderTaskSummary demPrepareTask = graphBuilderModuleSummary.addSubTask(
            "fetch and prepare elevation data"
        );
        log.info(demPrepareTask.start());

        gridCoverageFactory.setGraph(graph);
        Coverage gridCov = gridCoverageFactory.getGridCoverage();

        // If gridCov is a GridCoverage2D, apply a bilinear interpolator. Otherwise, just use the
        // coverage as is (note: UnifiedGridCoverages created by NEDGridCoverageFactoryImpl handle
        // interpolation internally)
        coverage = (gridCov instanceof GridCoverage2D) ? Interpolator2D.create(
                (GridCoverage2D) gridCov, new InterpolationBilinear()) : gridCov;
        // try to load in the cached elevation data
        if (readCachedElevations) {
            try {
                ObjectInputStream in = new ObjectInputStream(new FileInputStream(cachedElevationsFile));
                cachedElevations = (HashMap<String, PackedCoordinateSequence>) in.readObject();
                log.info("Cached elevation data loaded into memory!");
            } catch (IOException | ClassNotFoundException e) {
                log.warn(graph.addBuilderAnnotation(new Graphwide(
                    String.format("Cached elevations file could not be read in due to error: %s!", e.getMessage()))));
            }
        }
        log.info(demPrepareTask.finish());

        GraphBuilderTaskSummary setElevationsFromDEMTask = graphBuilderModuleSummary.addSubTask(
            "set elevation profiles with DEM"
        );
        log.info(setElevationsFromDEMTask.start());

        List<StreetEdge> edgesWithElevation = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger nProcessed = new AtomicInteger();

        List<StreetWithElevationEdge> edgesToCalculate = new ArrayList<>();
        for (Vertex gv : graph.getVertices()) {
            for (Edge ee : gv.getOutgoing()) {
                if (ee instanceof StreetWithElevationEdge) {
                    edgesToCalculate.add((StreetWithElevationEdge) ee);
                }
            }
        }

        edgesToCalculate.parallelStream().forEach(edgeWithElevation -> {
            processEdge(graph, edgeWithElevation);
            if (edgeWithElevation.hasPackedElevationProfile() && !edgeWithElevation.isElevationFlattened()) {
                edgesWithElevation.add(edgeWithElevation);
            }
            int curNumProcessed = nProcessed.addAndGet(1);
            if (curNumProcessed % 50000 == 0) {
                log.info("set elevation on {}/{} edges", curNumProcessed, edgesToCalculate.size());
            }
        });

        double failurePercentage = nPointsOutsideDEM.get() / nPointsEvaluated.get() * 100;
        if (failurePercentage > 50) {
            log.warn(graph.addBuilderAnnotation(new Graphwide(
                String.format(
                    "Fetching elevation failed at %d/%d points (%d%%)",
                    nPointsOutsideDEM, nPointsEvaluated, failurePercentage
                )
            )));
            log.warn("Elevation is missing at a large number of points. DEM may be for the wrong region. " +
                "If it is unprojected, perhaps the axes are not in (longitude, latitude) order.");
        }

        if (writeCachedElevations) {
            // write information from edgesWithElevation to a new cache file for subsequent graph builds
            HashMap<String, PackedCoordinateSequence> newCachedElevations = new HashMap<>();
            for (StreetEdge streetEdge : edgesWithElevation) {
                newCachedElevations.put(PolylineEncoder.createEncodings(streetEdge.getGeometry()).getPoints(),
                    streetEdge.getElevationProfile());
            }
            try {
                ObjectOutputStream out = new ObjectOutputStream(
                    new BufferedOutputStream(new FileOutputStream(cachedElevationsFile)));
                out.writeObject(newCachedElevations);
                out.close();
            } catch (IOException e) {
                log.error(e.getMessage());
                log.error(graph.addBuilderAnnotation(new Graphwide("Failed to write cached elevation file!")));
            }
        }
        log.info(setElevationsFromDEMTask.finish());

        GraphBuilderTaskSummary missingElevationsTask = graphBuilderModuleSummary.addSubTask(
            "calculate missing elevations"
        );
        log.info(missingElevationsTask.start());
        assignMissingElevations(graph, edgesWithElevation);
        log.info(missingElevationsTask.finish());
    }

    class ElevationRepairState {
        /* This uses an intuitionist approach to elevation inspection */
        public StreetEdge backEdge;

        public ElevationRepairState backState;

        public Vertex vertex;

        public double distance;

        public double initialElevation;

        public ElevationRepairState(StreetEdge backEdge, ElevationRepairState backState,
                Vertex vertex, double distance, double initialElevation) {
            this.backEdge = backEdge;
            this.backState = backState;
            this.vertex = vertex;
            this.distance = distance;
            this.initialElevation = initialElevation;
        }
    }

    /**
     * Assign missing elevations by interpolating from nearby points with known
     * elevation; also handle osm ele tags
     */
    private void assignMissingElevations(
        Graph graph,
        List<StreetEdge> edgesWithElevation
    ) {

        log.debug("Assigning missing elevations");

        BinHeap<ElevationRepairState> pq = new BinHeap<ElevationRepairState>();

        // elevation for each vertex (known or interpolated)
        // knownElevations will be null if there are no ElevationPoints in the data
        // for instance, with the Shapefile loader.)
        HashMap<Vertex, Double> elevations;
        HashMap<Vertex, Double> knownElevations = graph.getKnownElevations();
        if (knownElevations != null)
            elevations = (HashMap<Vertex, Double>) knownElevations.clone();
        else
            elevations = new HashMap<Vertex, Double>();

        HashSet<Vertex> closed = new HashSet<Vertex>();

        // initialize queue with all vertices which already have known elevation
        for (StreetEdge e : edgesWithElevation) {
            PackedCoordinateSequence profile = e.getElevationProfile();

            if (!elevations.containsKey(e.getFromVertex())) {
                double firstElevation = profile.getOrdinate(0, 1);
                ElevationRepairState state = new ElevationRepairState(null, null,
                        e.getFromVertex(), 0, firstElevation);
                pq.insert(state, 0);
                elevations.put(e.getFromVertex(), firstElevation);
            }

            if (!elevations.containsKey(e.getToVertex())) {
                double lastElevation = profile.getOrdinate(profile.size() - 1, 1);
                ElevationRepairState state = new ElevationRepairState(null, null, e.getToVertex(),
                        0, lastElevation);
                pq.insert(state, 0);
                elevations.put(e.getToVertex(), lastElevation);
            }
        }

        // Grow an SPT outward from vertices with known elevations into regions where the
        // elevation is not known. when a branch hits a region with known elevation, follow the
        // back pointers through the region of unknown elevation, setting elevations via interpolation.
        while (!pq.empty()) {
            ElevationRepairState state = pq.extract_min();

            if (closed.contains(state.vertex)) continue;
            closed.add(state.vertex);

            ElevationRepairState curState = state;
            Vertex initialVertex = null;
            while (curState != null) {
                initialVertex = curState.vertex;
                curState = curState.backState;
            }

            double bestDistance = Double.MAX_VALUE;
            double bestElevation = 0;
            for (Edge e : state.vertex.getOutgoing()) {
                if (!(e instanceof StreetEdge)) {
                    continue;
                }
                StreetEdge edge = (StreetEdge) e;
                Vertex tov = e.getToVertex();
                if (tov == initialVertex)
                    continue;

                Double elevation = elevations.get(tov);
                if (elevation != null) {
                    double distance = e.getDistance();
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestElevation = elevation;
                    }
                } else {
                    // continue
                    ElevationRepairState newState = new ElevationRepairState(edge, state, tov,
                            e.getDistance() + state.distance, state.initialElevation);
                    pq.insert(newState, e.getDistance() + state.distance);
                }
            } // end loop over outgoing edges

            for (Edge e : state.vertex.getIncoming()) {
                if (!(e instanceof StreetEdge)) {
                    continue;
                }
                StreetEdge edge = (StreetEdge) e;
                Vertex fromv = e.getFromVertex();
                if (fromv == initialVertex)
                    continue;
                Double elevation = elevations.get(fromv);
                if (elevation != null) {
                    double distance = e.getDistance();
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestElevation = elevation;
                    }
                } else {
                    // continue
                    ElevationRepairState newState = new ElevationRepairState(edge, state, fromv,
                            e.getDistance() + state.distance, state.initialElevation);
                    pq.insert(newState, e.getDistance() + state.distance);
                }
            } // end loop over incoming edges

            //limit elevation propagation to at max 2km; this prevents an infinite loop
            //in the case of islands missing elevation (and some other cases)
            if (bestDistance == Double.MAX_VALUE && state.distance > 2000) {
                log.warn("While propagating elevations, hit 2km distance limit at " + state.vertex);
                bestDistance = state.distance;
                bestElevation = state.initialElevation;
            }
            if (bestDistance != Double.MAX_VALUE) {
                // we have found a second vertex with elevation, so we can interpolate the elevation
                // for this point
                double totalDistance = bestDistance + state.distance;
                // trace backwards, setting states as we go
                while (true) {
                    // watch out for division by 0 here, which will propagate NaNs 
                    // all the way out to edge lengths 
                    if (totalDistance == 0)
                        elevations.put(state.vertex, bestElevation);
                    else {
                        double elevation = (bestElevation * state.distance + 
                               state.initialElevation * bestDistance) / totalDistance;
                        elevations.put(state.vertex, elevation);
                    }
                    if (state.backState == null)
                        break;
                    bestDistance += state.backEdge.getDistance();
                    state = state.backState;
                    if (elevations.containsKey(state.vertex))
                        break;
                }

            }
        } // end loop over states

        // do actual assignments
        for (Vertex v : graph.getVertices()) {
            Double fromElevation = elevations.get(v);
            for (Edge e : v.getOutgoing()) {
                if (e instanceof StreetWithElevationEdge) {
                    StreetWithElevationEdge edge = ((StreetWithElevationEdge) e);

                    Double toElevation = elevations.get(edge.getToVertex());

                    if (fromElevation == null || toElevation == null) {
                        if (!edge.isElevationFlattened() && !edge.isSlopeOverride())
                            log.warn("Unexpectedly missing elevation for edge " + edge);
                        continue;
                    }

                    if (edge.getElevationProfile() != null && edge.getElevationProfile().size() > 2) {
                        continue;
                    }

                    Coordinate[] coords = new Coordinate[2];
                    coords[0] = new Coordinate(0, fromElevation);
                    coords[1] = new Coordinate(edge.getDistance(), toElevation);

                    PackedCoordinateSequence profile = new PackedCoordinateSequence.Double(coords);

                    if (edge.setElevationProfile(profile, true)) {
                        log.trace(graph.addBuilderAnnotation(new ElevationFlattened(edge)));
                    }
                }
            }
        }
    }

    /**
     * Processes a single street edge, creating and assigning the elevation profile.
     * 
     * @param ee the street edge
     * @param graph the graph (used only for error handling)
     */
    private void processEdge(Graph graph, StreetWithElevationEdge ee) {
        if (ee.hasPackedElevationProfile()) {
            return; /* already set up */
        }
        Geometry g = ee.getGeometry();

        // first try to find a cached value if possible
        if (cachedElevations != null) {
            PackedCoordinateSequence coordinateSequence = cachedElevations.get(
                PolylineEncoder.createEncodings(g).getPoints()
            );
            // found a cached value!
            if (coordinateSequence != null) {
                setEdgeElevationProfile(ee, coordinateSequence, graph);
                return;
            }
        }

        // did not find a cached value, calculate
        // If any of the coordinates throw an error when trying to lookup their value, immediately bail and do not
        // process the elevation on the edge
        try {
            Coordinate[] coords = g.getCoordinates();

            List<Coordinate> coordList = new LinkedList<Coordinate>();

            // initial sample (x = 0)
            coordList.add(new Coordinate(0, getElevation(coords[0])));

            // iterate through coordinates calculating the edge length and creating intermediate elevation coordinates at
            // the regularly specified interval
            double edgeLenM = 0;
            double sampleDistance = distanceBetweenSamplesM;
            double previousDistance = 0;
            double x1 = coords[0].x, y1 = coords[0].y, x2, y2;
            for (int i = 0; i < coords.length - 1; i++) {
                x2 = coords[i + 1].x;
                y2 = coords[i + 1].y;
                double curSegmentDistance = SphericalDistanceLibrary.distance(y1, x1, y2, x2);
                edgeLenM += curSegmentDistance;
                while (edgeLenM > sampleDistance) {
                    // if current edge length is longer than the current sample distance, insert new elevation coordinates
                    // as needed until sample distance has caught up

                    // calculate percent of current segment that distance is between
                    double pctAlongSeg = (sampleDistance - previousDistance) / curSegmentDistance;
                    // add an elevation coordinate
                    coordList.add(
                        new Coordinate(
                            sampleDistance,
                            getElevation(
                                new Coordinate(
                                    x1 + (pctAlongSeg * (x2 - x1)),
                                    y1 + (pctAlongSeg * (y2 - y1))
                                )
                            )
                        )
                    );
                    sampleDistance += distanceBetweenSamplesM;
                }
                previousDistance = edgeLenM;
                x1 = x2;
                y1 = y2;
            }

            // remove final-segment sample if it is less than half the distance between samples
            if (edgeLenM - coordList.get(coordList.size() - 1).x < distanceBetweenSamplesM / 2) {
                coordList.remove(coordList.size() - 1);
            }

            // final sample (x = edge length)
            coordList.add(new Coordinate(edgeLenM, getElevation(coords[coords.length - 1])));

            // construct the PCS
            Coordinate coordArr[] = new Coordinate[coordList.size()];
            PackedCoordinateSequence elevPCS = new PackedCoordinateSequence.Double(
                    coordList.toArray(coordArr));

            setEdgeElevationProfile(ee, elevPCS, graph);
        } catch (PointOutsideCoverageException e) {
            log.debug("Error processing elevation for edge: {} due to error: {}", ee, e);
        }
    }

    private void setEdgeElevationProfile(StreetWithElevationEdge ee, PackedCoordinateSequence elevPCS, Graph graph) {
        if(ee.setElevationProfile(elevPCS, false)) {
            synchronized (graph) {
                log.trace(graph.addBuilderAnnotation(new ElevationFlattened(ee)));
            }
        }
    }

    /**
     * Method for retrieving the elevation at a given Coordinate.
     * 
     * @param c the coordinate (NAD83)
     * @return elevation in meters
     */
    private double getElevation(Coordinate c) throws PointOutsideCoverageException {
        return getElevation(c.x, c.y);
    }

    /**
     * Method for retrieving the elevation at a given (x, y) pair.
     * 
     * @param x the query longitude (NAD83)
     * @param y the query latitude (NAD83)
     * @return elevation in meters
     */
    private double getElevation(double x, double y) throws PointOutsideCoverageException {
        double[] values = new double[1];
        try {
            // We specify a CRS here because otherwise the coordinates are assumed to be in the coverage's native CRS.
            // That assumption is fine when the coverage happens to be in longitude-first WGS84 but we want to support
            // GeoTIFFs in various projections. Note that GeoTools defaults to strict EPSG axis ordering of (lat, long)
            // for DefaultGeographicCRS.WGS84, but OTP is using (long, lat) throughout and assumes unprojected DEM
            // rasters to also use (long, lat).
            coverage.evaluate(new DirectPosition2D(GeometryUtils.WGS84_XY, x, y), values);
        } catch (PointOutsideCoverageException e) {
            nPointsOutsideDEM.incrementAndGet();
            throw e;
        }
        nPointsEvaluated.incrementAndGet();
        return values[0];
    }

    @Override
    public void checkInputs() {
        gridCoverageFactory.checkInputs();

        // check for the existence of cached elevation data.
        if (readCachedElevations) {
            if (Files.exists(cachedElevationsFile.toPath())) {
                log.info("Cached elevations file found!");
            } else {
                log.warn("No cached elevations file found or read access not allowed! Unable to load in cached elevations. This could take a while...");
            }
        } else {
            log.warn("Not using cached elevations! This could take a while...");
        }
    }

}