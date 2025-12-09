package com.rspsi.tools;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.major.map.RenderFlags;

import com.jagex.Client;
import com.jagex.Client.LoadState;
import com.jagex.map.tile.SceneTile;
import com.jagex.util.BitFlag;
import com.rspsi.options.Options;

public class ObjFiller {

    public static void fillObjs(int objId, int objType, int rotation) throws Exception {
        Client client = Client.getSingleton();
        if(client.loadState == LoadState.ACTIVE) {

            List<SceneTile> selectedTiles = client.sceneGraph.getSelectedTiles();
            client.sceneGraph.deleteObjects();
            for (SceneTile tile: selectedTiles) {
                client.sceneGraph.addObject(tile.positionX, tile.positionY, tile.plane, objId, objType, rotation, false);
            }

        }
    }

}
