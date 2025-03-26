package com.rspsi.game.save.tile;

import com.jagex.Client;
import com.rspsi.game.save.StateChangeType;
import com.rspsi.game.save.TileChange;
import com.rspsi.game.save.tile.state.OverlayState;


public class OverlayChange extends TileChange<OverlayState> {
	
	public OverlayChange() {
		super();
	}
	
	@Override
	public void restoreStates() {
		for(OverlayState state : preservedTileStates.values()) {
			int x = state.getX();
			int y = state.getY();
			int z = state.getZ();
			//System.out.println("LOADING " + x + ":" + y + ":" + z);
			Client.getSingleton().mapRegion.overlayRotation[z][x][y] = state.getRotation();
			Client.getSingleton().mapRegion.overlayIds[z][x][y] = state.getId();
			Client.getSingleton().mapRegion.overlayShape[z][x][y] = state.getShape();
		}
	}
	
	@Override
	public OverlayChange getInverse() {
		{
			try {
				OverlayChange change = new OverlayChange();
				preservedTileStates.values().forEach(state -> {
					try {
						OverlayState newState = new OverlayState(state.getX(), state.getY(), state.getZ());
						newState.preserve();
						change.preserveTileState(newState);
					} catch (Exception e) {
						e.printStackTrace();
					}
				});
				return change;
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			return null;
			
		}
	}

	@Override
	public StateChangeType getType() {
		return StateChangeType.OVERLAY;
	}

}
