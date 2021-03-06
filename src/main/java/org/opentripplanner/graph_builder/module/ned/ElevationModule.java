package org.opentripplanner.graph_builder.module.ned;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import org.geotools.geometry.DirectPosition2D;
import org.opengis.coverage.Coverage;
import org.opengis.coverage.PointOutsideCoverageException;
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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.opentripplanner.util.ElevationUtils.computeEllipsoidToGeoidDifference;

/**
 * {@link org.opentripplanner.graph_builder.services.GraphBuilderModule} plugin that applies elevation data to street
 * data that has already been loaded into a (@link Graph}, creating elevation profiles for each Street encountered
 * in the Graph. Data sources that could be used include auto-downloaded and cached National Elevation Dataset (NED)
 * raster data or a GeoTIFF file. The elevation profiles are stored as {@link PackedCoordinateSequence} objects, where
 * each (x,y) pair represents one sample, with the x-coord representing the distance along the edge measured from the
 * start, and the y-coord representing the sampled elevation at that point (both in meters).
 */
public class ElevationModule implements GraphBuilderModule {

    private static final Logger log = LoggerFactory.getLogger(ElevationModule.class);

    /** The elevation data to be used in calculating elevations. */
    private final ElevationGridCoverageFactory gridCoverageFactory;
    /* Whether or not to attempt reading in a file of cached elevations */
    private final boolean readCachedElevations;
    /* Whether or not to attempt writing out a file of cached elevations */
    private final boolean writeCachedElevations;
    /* The file of cached elevations */
    private final File cachedElevationsFile;
    /* Whether or not to include geoid difference values in individual elevation calculations */
    private final boolean includeEllipsoidToGeoidDifference;

    private HashMap<String, PackedCoordinateSequence> cachedElevations;

    // Keep track of the proportion of elevation fetch operations that fail so we can issue warnings. AtomicInteger is
    // used to provide thread-safe updating capabilities.
    private AtomicInteger nEdgesProcessed = new AtomicInteger(0);
    private final AtomicInteger nPointsEvaluated = new AtomicInteger(0);
    private final AtomicInteger nPointsOutsideDEM = new AtomicInteger(0);
    /** keeps track of the total amount of elevation edges for logging purposes */
    private int totalElevationEdges = Integer.MAX_VALUE;

    /**
     * The distance between samples in meters. Defaults to 10m, the approximate resolution of 1/3
     * arc-second NED data.
     */
    private double distanceBetweenSamplesM = 10;

    /** the graph being built */
    private Graph graph;

    /** A concurrent hashmap used for storing geoid difference values at various coordinates */
    private final ConcurrentHashMap<Integer, Double> geoidDifferenceCache = new ConcurrentHashMap<>();

    // used only for testing purposes
    public ElevationModule(ElevationGridCoverageFactory factory) {
        gridCoverageFactory = factory;
        cachedElevationsFile = null;
        readCachedElevations = false;
        writeCachedElevations = false;
        includeEllipsoidToGeoidDifference = true;
    }

    public ElevationModule(
        ElevationGridCoverageFactory factory,
        File cacheDirectory,
        boolean readCachedElevations,
        boolean writeCachedElevations,
        boolean includeEllipsoidToGeoidDifference
    ) {
        gridCoverageFactory = factory;
        cachedElevationsFile = new File(cacheDirectory, "cached_elevations.obj");
        this.readCachedElevations = readCachedElevations;
        this.writeCachedElevations = writeCachedElevations;
        this.includeEllipsoidToGeoidDifference = includeEllipsoidToGeoidDifference;
    }

    public List<String> provides() {
        return Arrays.asList("elevation");
    }

    public List<String> getPrerequisites() {
        return Arrays.asList("streets");
    }

    @Override
    public void buildGraph(Graph graph, GraphBuilderModuleSummary graphBuilderModuleSummary) {
        this.graph = graph;
        GraphBuilderTaskSummary demPrepareTask = graphBuilderModuleSummary.addSubTask(
            "fetch and prepare elevation data"
        );
        log.info(demPrepareTask.start());

        gridCoverageFactory.fetchData(graph);

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

        // Multithread elevation calculations
        ForkJoinPool forkJoinPool = new ForkJoinPool();

        // For unknown reasons, the interpolation of heights at coordinates is a synchronized method in the commonly
        // used Interpolator2D class. Therefore, it is critical to use a dedicated Coverage instance for each thread to
        // avoid other threads waiting for a lock to be released on the Coverage instance. This concurrent HashMap will
        // store these thread-specific Coverage instances.
        ConcurrentHashMap<Long, Coverage> coveragesForThread = new ConcurrentHashMap<>();

        // At first, set the totalElevationEdges to the total number of edges in the graph.
        totalElevationEdges = graph.countEdges();
        List<StreetWithElevationEdge> streetsWithElevationEdges = new LinkedList<>();
        for (Vertex gv : graph.getVertices()) {
            for (Edge ee : gv.getOutgoing()) {
                if (ee instanceof StreetWithElevationEdge) {
                    forkJoinPool.submit(new ProcessEdgeTask((StreetWithElevationEdge) ee, coveragesForThread));
                    streetsWithElevationEdges.add((StreetWithElevationEdge) ee);
                }
            }
        }
        // update this value to the now-known amount of edges that are StreetWithElevation edges
        totalElevationEdges = streetsWithElevationEdges.size();

        // shutdown the forkJoinPool and wait until all tasks are finished. If this takes longer than 1 day, give up.
        forkJoinPool.shutdown();
        try {
            forkJoinPool.awaitTermination(1, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            log.warn(graph.addBuilderAnnotation(new Graphwide("Multi-threaded elevation calculations timed-out!")));
        }

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

        // iterate again to find edges that had elevation calculated. This is done here instead of in the forkJoinPool
        // to avoid thread locking for writes to a synchronized list
        LinkedList<StreetEdge> edgesWithCalculatedElevations = new LinkedList<>();
        for (StreetWithElevationEdge edgeWithElevation : streetsWithElevationEdges) {
            if (edgeWithElevation.hasPackedElevationProfile() && !edgeWithElevation.isElevationFlattened()) {
                edgesWithCalculatedElevations.add(edgeWithElevation);
            }
        }

        if (writeCachedElevations) {
            // write information from edgesWithElevation to a new cache file for subsequent graph builds
            HashMap<String, PackedCoordinateSequence> newCachedElevations = new HashMap<>();
            for (StreetEdge streetEdge : edgesWithCalculatedElevations) {
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
        assignMissingElevations(graph, edgesWithCalculatedElevations);
        log.info(missingElevationsTask.finish());
    }

    /**
     * A runnable that contains the relevant info for executing a process edge operation in a particular thread.
     */
    private class ProcessEdgeTask implements Runnable {
        private final StreetWithElevationEdge swee;
        private final ConcurrentHashMap<Long, Coverage> coveragesForThread;

        public ProcessEdgeTask(StreetWithElevationEdge swee, ConcurrentHashMap<Long, Coverage> coveragesForThread) {
            this.swee = swee;
            this.coveragesForThread = coveragesForThread;
        }

        @Override public void run() {
            processEdge(swee, getThreadSpecificCoverageInstance());
            int curNumProcessed = nEdgesProcessed.addAndGet(1);
            if (curNumProcessed % 50000 == 0) {
                log.info("set elevation on {}/{} edges", curNumProcessed, totalElevationEdges);
            }
        }

        /**
         * Get the thread-specific Coverage instance to avoid multiple threads waiting for a lock to be released on a
         * Interpolator2D instance.
         */
        private Coverage getThreadSpecificCoverageInstance () {
            long currentThreadId = Thread.currentThread().getId();
            Coverage threadSpecificCoverage = coveragesForThread.get(currentThreadId);
            if (threadSpecificCoverage == null) {
                // Synchronize the creation of the Thread-specific Coverage instances to avoid potential locks that
                // could arise from downstream classes that have synchronized methods.
                synchronized (coveragesForThread) {
                    // Get a new Coverage instance from the module's ElevationGridCoverageFactory.
                    threadSpecificCoverage = gridCoverageFactory.getGridCoverage();
                    // The Coverage instance relies on some synchronized static methods shared across all threads that
                    // can cause deadlocks if not fully initialized. Therefore, make a single request for the first
                    // point on the edge to initialize these other items.
                    Coordinate firstEdgeCoord =  swee.getGeometry().getCoordinates()[0];
                    double[] dummy = new double[1];
                    threadSpecificCoverage.evaluate(
                        new DirectPosition2D(GeometryUtils.WGS84_XY, firstEdgeCoord.x, firstEdgeCoord.y),
                        dummy
                    );
                }
                coveragesForThread.put(currentThreadId, threadSpecificCoverage);
            }
            return threadSpecificCoverage;
        }
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
        LinkedList<StreetEdge> edgesWithElevation
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
     * @param coverage the specific Coverage instance to use in order to avoid competition between threads
     */
    private void processEdge(StreetWithElevationEdge ee, Coverage coverage) {
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
            coordList.add(new Coordinate(0, getElevation(coverage, coords[0])));

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
                                coverage,
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
            coordList.add(new Coordinate(edgeLenM, getElevation(coverage, coords[coords.length - 1])));

            // construct the PCS
            Coordinate coordArr[] = new Coordinate[coordList.size()];
            PackedCoordinateSequence elevPCS = new PackedCoordinateSequence.Double(
                    coordList.toArray(coordArr));

            setEdgeElevationProfile(ee, elevPCS, graph);
        } catch (PointOutsideCoverageException | TransformException e) {
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
     * @param coverage the specific Coverage instance to use in order to avoid competition between threads
     * @param c the coordinate (NAD83)
     * @return elevation in meters
     */
    private double getElevation(Coverage coverage, Coordinate c) throws PointOutsideCoverageException, TransformException {
        return getElevation(coverage, c.x, c.y);
    }

    /**
     * Method for retrieving the elevation at a given (x, y) pair.
     *
     * @param coverage the specific Coverage instance to use in order to avoid competition between threads
     * @param x the query longitude (NAD83)
     * @param y the query latitude (NAD83)
     * @return elevation in meters
     */
    private double getElevation(Coverage coverage, double x, double y) throws PointOutsideCoverageException, TransformException {
        double values[] = new double[1];
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
        return values[0] - (includeEllipsoidToGeoidDifference ? getApproximateEllipsoidToGeoidDifference(y, x) : 0);
    }

    /**
     * The Calculation of the EllipsoidToGeoidDifference is a very expensive operation, so the resulting values are
     * cached based on the coordinate values up to 2 significant digits. Two significant digits are often more than enough
     * for most parts of the world, but is useful for certain areas that have dramatic changes. Since the values are
     * computed once and cached, it has almost no affect on performance to have this level of detail.
     * See this image for an approximate mapping of these difference values:
     * https://earth-info.nga.mil/GandG/images/ww15mgh2.gif
     *
     * @param y latitude
     * @param x longitude
     */
    private double getApproximateEllipsoidToGeoidDifference(double y, double x) throws TransformException {
        int geoidDifferenceCoordinateValueMultiplier = 100;
        int xVal = (int) Math.round(x * geoidDifferenceCoordinateValueMultiplier);
        int yVal = (int) Math.round(y * geoidDifferenceCoordinateValueMultiplier);
        // create a hash value that can be used to look up the value for the given rounded coordinate. The expected
        // value of xVal should never be less than -18000 (-180 * 100) or more than 18000 (180 * 100), so multiply the
        // yVal by a prime number of a magnitude larger so that there won't be any hash collisions.
        int hash = yVal * 104729 + xVal;
        Double difference = geoidDifferenceCache.get(hash);
        if (difference == null) {
            difference = computeEllipsoidToGeoidDifference(
                yVal / (1.0 * geoidDifferenceCoordinateValueMultiplier),
                xVal / (1.0 * geoidDifferenceCoordinateValueMultiplier)
            );
            geoidDifferenceCache.put(hash, difference);
        }
        return difference;
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