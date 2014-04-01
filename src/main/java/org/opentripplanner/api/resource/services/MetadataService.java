package org.opentripplanner.api.resource.services;

import java.util.HashMap;

import org.opentripplanner.api.resource.GraphMetadata;
import org.opentripplanner.routing.services.GraphService;

public class MetadataService {

    private GraphService graphService;

    public MetadataService(GraphService graphService) {
        this.graphService = graphService;
    }

    HashMap<String, GraphMetadata> metadata = new HashMap<String, GraphMetadata>();
    
    public synchronized GraphMetadata getMetadata(String routerId) {
        GraphMetadata data = metadata.get(routerId);
        if (data == null) {
            data = new GraphMetadata(graphService.getGraph(routerId));
            metadata.put(routerId, data);
        }
        return data;
    }

    public GraphService getGraphService() {
        return graphService;
    }

    public void setGraphService(GraphService graphService) {
        this.graphService = graphService;
    }

}
