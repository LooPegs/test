package com.rspsi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jagex.map.MapRegion;
import com.jagex.map.tile.SceneTile;
import com.rspsi.dialogs.RenderDistanceDialog;
import com.rspsi.game.save.tile.state.OverlayState;
import com.rspsi.misc.JsonUtil;
import com.rspsi.options.KeyboardState;
import com.rspsi.util.*;
import javafx.beans.value.ChangeListener;
import org.displee.utilities.GZIPUtils;

import java.awt.*;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.major.map.RenderFlags;

import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.jagex.Client;
import com.jagex.cache.def.Floor;
import com.jagex.cache.def.ObjectDefinition;
import com.jagex.cache.loader.floor.FloorDefinitionLoader;
import com.jagex.cache.loader.object.ObjectDefinitionLoader;
import com.jagex.cache.loader.textures.TextureLoader;
import com.jagex.chunk.Chunk;
import com.jagex.draw.textures.Texture;
import com.jagex.entity.model.Mesh;
import com.jagex.entity.model.MeshLoader;
import com.jagex.map.SceneGraph;
import com.jagex.map.object.DefaultWorldObject;
import com.jagex.util.BitFlag;
import com.jagex.util.ColourUtils;
import com.jagex.util.MultiMapEncoder;
import com.jagex.util.ObjectKey;
import com.rspsi.controllers.MainController;
import com.rspsi.controls.RemappingTool;
import com.rspsi.controls.SwatchControl;
import com.rspsi.datasets.ObjectDataset;
import com.rspsi.dialogs.TileCopyDialog;
import com.rspsi.dialogs.TileDeleteDialog;
import com.rspsi.dialogs.TileExportDialog;
import com.rspsi.game.CanvasPane;
import com.rspsi.game.listeners.GameKeyListener;
import com.rspsi.game.listeners.GameMouseListener;
import com.rspsi.game.map.MapView;
import com.rspsi.game.save.AutoSaveJob;
import com.rspsi.game.save.TileChange;
import com.rspsi.misc.StatusUpdate;
import com.rspsi.misc.ToolType;
import com.rspsi.misc.XTEAManager;
import com.rspsi.options.Config;
import com.rspsi.options.Options;
import com.rspsi.plugins.ApplicationPluginLoader;
import com.rspsi.resources.ResourceLoader;
import com.rspsi.swatches.BaseSwatch;
import com.rspsi.swatches.OverlaySwatch;
import com.rspsi.swatches.UnderlaySwatch;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Getter
public class MainWindow extends Application {

	public static class TileData {
		public int plane;
		public int x;
		public int y;
		public int overlayid;
		public int shape;
		public int rotation;
		public TileData(int plane, int x, int y, int overlayid, int shape, int rotation) {
			this.plane = plane;
			this.x = x;
			this.y = y;
			this.overlayid = overlayid;
			this.shape = shape;
			this.rotation = rotation;
		}
	}

	private static MainWindow singleton;

	/**
	 * Rotate an 8-bit value left (circular shift).
	 * @param value the 8-bit value (0-255)
	 * @param count number of positions to rotate
	 * @return rotated 8-bit value (0-255)
	 */
	public static int rotateLeft8(int value, int count) {
		value &= 0xFF;       // ensure 8-bit
		count &= 7;          // only 0-7 matter for 8 bits
		return ((value << count) | (value >>> (8 - count))) & 0xFF;
	}

	static {

		//Faster tooltips
		try {
			Tooltip obj = new Tooltip();
			Class<?> clazz = obj.getClass().getDeclaredClasses()[0];
			Constructor<?> constructor = clazz.getDeclaredConstructor(Duration.class, Duration.class, Duration.class,
					boolean.class);
			constructor.setAccessible(true);
			Object tooltipBehavior = constructor.newInstance(new Duration(50), // open
					new Duration(5000), // visible
					new Duration(200), // close
					false);
			Field fieldBehavior = obj.getClass().getDeclaredField("BEHAVIOR");
			fieldBehavior.setAccessible(true);
			fieldBehavior.set(obj, tooltipBehavior);
		} catch (Exception e) {
			// Logger.error(e);
		}
	}


	private Client clientInstance;

	private Scene scene;

	private Stage stage;

	private MainController controller;

	@Setter
	public SwatchControl objectSwatch, overlaySwatch, underlaySwatch;

	private ObjectPreviewWindow objectPreviewWindow;
	private PickCoordinatesWindow pickCoords;
	private PickHashWindow pickHash;
	private MultiRegionMapWindow fullMapView;
	private SelectFilesWindow selectFiles;
	private SelectPackWindow selectPack;
	private SelectXTEAWindow selectXTEA;
	private RemappingTool remappingTool;

	private Mesh errorMesh;

	public void fillSwatches() {

		for (int idx = 0; idx < FloorDefinitionLoader.getUnderlayCount(); idx++) {
			Floor floor = FloorDefinitionLoader.getUnderlay(idx);
			if(floor == null)
				continue;
			Group g = new Group();
			String label = "";
			label = "[" + idx + "] rgb(" + ColourUtils.getRed(floor.getRgb()) + "," + ColourUtils.getGreen(floor.getRgb()) + ","
					+ ColourUtils.getBlue(floor.getRgb()) + ")";
			Rectangle rect = new Rectangle();
			rect.setWidth(32);
			rect.setHeight(32);
			Color c = ColourUtils.getColor(floor.getRgb());
			// c = c.deriveColor(floor.getWeightedHue(), floor.getSaturation() / 256.0,
			// floor.getLuminance() / 256.0, 1.0);
			rect.setFill(c);
			rect.setStroke(Color.BLACK);
			rect.setStrokeWidth(1);
			g.getChildren().add(rect);

			BaseSwatch data = new UnderlaySwatch(g, label, idx);
			underlaySwatch.addSwatch(data);
		}
		for (int idx = 0; idx < FloorDefinitionLoader.getOverlayCount(); idx++) {
			Floor floor = FloorDefinitionLoader.getOverlay(idx);

			if(floor == null)
				continue;
			Group g = new Group();
			String label = "";
			if (floor.getTexture() == -1 || floor.getTexture() > TextureLoader.instance.count()) {
				continue;
			} else {
				label = "[" + idx + "] texture(" + floor.getTexture() + ")";
				Texture texture = TextureLoader.getTexture(floor.getTexture());
				if(texture == null)
					continue;
				ImageView imgView = new ImageView(texture.getAsFXImage());
				imgView.setPreserveRatio(true);
				imgView.setSmooth(true);
				imgView.setFitHeight(32);
				imgView.setFitWidth(32);
				g.getChildren().add(imgView);
			}
			BaseSwatch data = new OverlaySwatch(g, label, idx);
			overlaySwatch.addSwatch(data);
		}
		for (int idx = 0; idx < FloorDefinitionLoader.getOverlayCount(); idx++) {

			Floor floor = FloorDefinitionLoader.getOverlay(idx);

			if(floor == null)
				continue;
			Group g = new Group();
			String label = "";
			if (floor.getTexture() == -1 || floor.getTexture() >= TextureLoader.instance.count()) {
				label = "[" + idx + "] rgb(" + ColourUtils.getRed(floor.getRgb()) + "," + ColourUtils.getGreen(floor.getRgb()) + "," + ColourUtils.getBlue(floor.getRgb()) + ")";
				Rectangle rect = new Rectangle();
				rect.setWidth(32);
				rect.setHeight(32);
				Color c = ColourUtils.getColor(floor.getRgb());
				rect.setFill(c);
				rect.setStroke(Color.BLACK);
				rect.setStrokeWidth(1);
				g.getChildren().add(rect);
			} else {
				continue;
			}
			BaseSwatch data = new OverlaySwatch(g, label, idx);
			overlaySwatch.addSwatch(data);
		}

	}

	@Override
	public void start(Stage primaryStage) {
		try {
			singleton = this;
			stage = primaryStage;
			Platform.setImplicitExit(true);
			FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main_test4.fxml"));
			controller = new MainController();
			loader.setController(controller);
			Parent content = loader.load();
			double windowWidth = (Double) Settings.properties.getOrDefault("window_width",1240.0);
			double windowHeight = (Double) Settings.properties.getOrDefault("window_height",800.0);
			scene = new Scene(content,windowWidth,windowHeight);

			scene.setFill(Color.TRANSPARENT);

			primaryStage.setTitle("RSPSi Map Editor 1.16.1");
			primaryStage.initStyle(StageStyle.TRANSPARENT);
			primaryStage.setScene(scene);
			primaryStage.getIcons().addAll(ResourceLoader.getSingleton().getIcons());

			ChangeListener<Number> stageSizeListener = (observable, oldValue, newValue) -> {
				if((boolean) Settings.properties.getOrDefault("remember_size",true) == true) {
					Settings.properties.put("window_width", primaryStage.getWidth());
					Settings.properties.put("window_height", primaryStage.getHeight());
					Settings.saveSettings();
				}
			};

			ChangeListener<Number> stageLocationListener = (observable, oldValue, newValue) -> {
				if((boolean) Settings.properties.getOrDefault("remember_location",false) == true) {
					Settings.properties.put("windowLocationWidth", primaryStage.getX());
					Settings.properties.put("windowLocationHeight", primaryStage.getY());
					Settings.saveSettings();
				}
			};

			double windowLocationWidth = (Double) Settings.properties.getOrDefault("windowLocationWidth",0.0);
			double windowLocationHeight = (Double) Settings.properties.getOrDefault("windowLocationHeight",0.0);

			if(windowLocationWidth == 0.0 && windowLocationHeight == 0.0) {
				primaryStage.centerOnScreen();
			} else {
				int screenWidth = Toolkit.getDefaultToolkit().getScreenSize().width;
				int screenHeight = Toolkit.getDefaultToolkit().getScreenSize().height;
				if (windowLocationWidth <= screenWidth && windowLocationHeight <= screenHeight) {
					primaryStage.setX(windowLocationWidth);
					primaryStage.setY(windowLocationHeight);
				} else {
					primaryStage.centerOnScreen();
				}
			}

			primaryStage.widthProperty().addListener(stageSizeListener);
			primaryStage.heightProperty().addListener(stageSizeListener);
			primaryStage.xProperty().addListener(stageLocationListener);
			primaryStage.yProperty().addListener(stageLocationListener);

			primaryStage.show();

			FXUtils.centerStage(primaryStage);

			controller.onLoad(this);

			boolean shutdownCorrectly = Settings.getSetting("shutdown", false);
			Settings.clearSetting("shutdown");

			int renderDistance = Settings.getSetting("renderDistance", Options.renderDistance.get());

			Options.renderDistance.set(renderDistance);

			boolean loadAutosave = false;
			File autosavePath = Paths.get(System.getProperty("user.home"), ".rspsi", "autosave").toFile();

			String lastCacheLoc = Settings.getSetting("lastCacheLocation", "");
			if(!shutdownCorrectly) {
				System.out.println("CRASH DETECTED!");

				if(autosavePath.exists() && autosavePath.list().length > 0) {

					//Just incase there was a crash mid autosave
					File packFile = new File(autosavePath, "autosave.pack");

					File objectFileBackup =  new File(autosavePath, "autosave.pack.bk");

					//restore backups
					if(objectFileBackup.exists()) {
						Files.copy(objectFileBackup.toPath(), packFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
					}


					String response = FXDialogs.showConfirm(primaryStage,"Application did not shut down correctly!",
							"We have detected that your last shutdown did not complete correctly.\nWould you like to load the last autosave?",
							"Yes", "No");
					if(response.equalsIgnoreCase("yes")) {
						loadAutosave = true;
					}
				}
			}

			MapView mapView = new MapView();
			
			selectXTEA = new SelectXTEAWindow();
			selectXTEA.start(new Stage());

			TileExportDialog export = new TileExportDialog();
			export.start(new Stage());

			TileDeleteDialog deleteWindow = new TileDeleteDialog();
			deleteWindow.start(new Stage());

			TileCopyDialog copyWindow = new TileCopyDialog();
			copyWindow.start(new Stage());

			pickCoords = new PickCoordinatesWindow();
			pickCoords.start(new Stage());

			pickHash = new PickHashWindow();
			pickHash.start(new Stage());

			selectFiles = new SelectFilesWindow();
			selectFiles.start(new Stage());

			selectPack = new SelectPackWindow();
			selectPack.start(new Stage());

			ContactMeWindow contactMe = new ContactMeWindow();
			contactMe.start(new Stage());

			controller.getContactMeBtn().setOnAction(evt -> {
				contactMe.showAndWait();
			});

			controller.getShowMapIndexEditor().setOnAction(evt -> {
				mapView.setVisible(true);
				mapView.initTiles();
			});



			ChangeListenerUtil.addRangeListener(Options.rotation, 0, 3, true);

			ChangeListenerUtil.addListener(() -> {
				clientInstance.getCurrentChunk().mapRegion.updateTiles();
				Client.updateChunkTiles();
				SceneGraph.minimapUpdate = true;
			}, Options.disableBlending, Options.showOverlay, Options.showObjects);

			controller.getReloadSwatchesBtn().setOnAction(evt -> {
				overlaySwatch.clear();
				underlaySwatch.clear();
				fillSwatches();
			});
			controller.getReloadModelsBtn().setOnAction(evt -> MeshLoader.getSingleton().clearAll());
			controller.getCopySelectedTilesBtn().setOnAction(evt -> {
				if(Options.currentTool.get() == ToolType.SELECT_OBJECT) {
					SceneGraph.onCycleEnd.add(() -> {
						Client.getSingleton().sceneGraph.copyObjects();
					});

					controller.getPasteTilesBtn().setDisable(false);
				} else {
					copyWindow.show();
					controller.getPasteTilesBtn().setDisable(false);
				}
			});

			controller.getPasteTilesBtn().setOnAction(evt -> {

				SceneGraph scene = clientInstance.sceneGraph;
				scene.resetTiles();
				Options.currentTool.set(ToolType.IMPORT_SELECTION);

			});

			SceneGraph.undoList.addListener((ListChangeListener<TileChange>) listener -> {
				controller.getUndoMenuItem().disableProperty().set(SceneGraph.undoList.isEmpty());
			});

			SceneGraph.redoList.addListener((ListChangeListener<TileChange>) listener -> {
				controller.getRedoMenuItem().disableProperty().set(SceneGraph.redoList.isEmpty());
			});

			controller.getUndoMenuItem().setOnAction(evt -> SceneGraph.undo());
			controller.getRedoMenuItem().setOnAction(evt -> SceneGraph.redo());

			controller.getDeleteSelectedTilesBtn().setOnAction(evt -> TileDeleteDialog.instance.show());

			RenderDistanceDialog renderDistanceDialog = new RenderDistanceDialog();
			renderDistanceDialog.start(new Stage());
			controller.getChangeViewDist().setOnAction(evt -> {
				renderDistanceDialog.show();
			});

			controller.getAddObjectToSwatchBtn().setOnAction(evt ->{
				
				if(!clientInstance.sceneGraph.selectedObjects.isEmpty()) {
					for(DefaultWorldObject selectedObject : clientInstance.sceneGraph.selectedObjects) {
						ObjectKey key = selectedObject.getKey();
						int id = key.getId();
						int type = key.getType();
						ObjectDefinition def = ObjectDefinitionLoader.lookup(id);

						ObjectDataset set = new ObjectDataset(id, type, def.getName());
						ObjectPreviewWindow.instance.loadToSwatches(set);
					}
				}
			});

			controller.generateShapesBtn.setOnAction(evt -> {
				Client client = Client.getSingleton();
				List<SceneTile> selectedTiles = client.sceneGraph.getSelectedTiles();

				int size = selectedTiles.size();
				if (size == 0) {
					FXDialogs.showError(MainWindow.getSingleton().getStage().getOwner(),"Error", "No tiles selected");
					return;
				}

				SceneTile startTile = selectedTiles.get(0);
				SceneTile endTile = selectedTiles.get(size-1);

				int height = Math.abs(startTile.positionY - endTile.positionY) + 1;
				int width = Math.abs(startTile.positionX - endTile.positionX) + 1;
				//not a catchall for rectangles
				if (size != height * width) {
					FXDialogs.showError(MainWindow.getSingleton().getStage().getOwner(),"Error", "Rectangle please");
					return;
				}

				//assume only one overlay id
				int overlayid = -1;
				//assume same plane
				int plane = startTile.plane;

				//mark tiles with overlay
				// 1 = overlay shape 1 detected
				// 0 = not detected
				// -1 = skip
				int [][] overlays = new int[width][height];
				for (int i = 0; i < size; i++) {
					SceneTile tile = selectedTiles.get(i);

					//System.out.println("x: " + tile.positionX + " y: " + tile.positionY + " overlayid: " + client.sceneGraph.getMapRegion().overlayIds[plane][tile.positionX][tile.positionY]);

					// square shape overlay
					if (tile.shape == null && client.sceneGraph.getMapRegion().overlayIds[plane][tile.positionX][tile.positionY] > 0) {
						overlays[i / height][i % height] = 1;
//						//test
//						client.sceneGraph.getMapRegion().overlayIds[plane][tile.positionX][tile.positionY] = 9;

						//get the overlay id we're working with if we haven't already. assume only one overlay id
						if (overlayid == -1) overlayid = client.sceneGraph.getMapRegion().overlayIds[plane][tile.positionX][tile.positionY] & 0xFF;
					}
				}

				if (overlayid == -1) {
					FXDialogs.showError(MainWindow.getSingleton().getStage().getOwner(),"Error", "Where my overlay");
					return;
				}

				int[] dx = {0, 1, 1, 1, 0, -1, -1, -1}; // column offset
				int[] dy = {1, 1, 0,-1,-1, -1, 0,  1}; // row offset
				//now harvest corners from overlays array (check surrounding tiles)
				//LinkedList<CoordPair> corners = new LinkedList<CoordPair>();
				LinkedList<TileData> changedTiles = new LinkedList<>();
				for (int x = 0; x < width; x++) {
					big_loop: for (int y = 0; y < height; y++) {

						if (overlays[x][y] == -1) continue;

							int surroundingTiles = 0;
							for (int i = 0; i < 8; i++) {
								int nx = x + dx[i];
								int ny = y + dy[i];

								//boolean hasOverlay = false;
								if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
									if (overlays[nx][ny] == 1) {
										surroundingTiles |= 1 << i;
									}
								}
							}
							//System.out.println(Integer.toBinaryString(surroundingTiles));

							if (surroundingTiles != 0) {

                                    int shape = 11;
                                    int rotation = -1;

                                    int xcoord = startTile.positionX + x;
                                    int ycoord = startTile.positionY + y;

									int mask;
									int pattern;

//                                    int r1 = Integer.rotateLeft(surroundingTiles, 2);
//                                    int r2 = Integer.rotateLeft(surroundingTiles, 4);
//                                    int r3 = Integer.rotateLeft(surroundingTiles, 6);
								check_surrounding:
								{
									//empty surrounded by three orthogonal
									if (overlays[x][y] == 0) {
										if (Integer.bitCount(surroundingTiles & 0b0101_0101) == 3) {
											shape = 0;
											rotation = 0;
											System.out.println("3 orthogonal success:" + Integer.toBinaryString(surroundingTiles));
											break check_surrounding;
										}
									}

									//add long diagonal. _| orientation
									pattern = 0b0001_0110;
									mask = 0b1111_0111;
									shape = 4;
									if (overlays[x][y] == 0) {
										for (int j = 0; j < 4; j++) {
											if ((surroundingTiles & MainWindow.rotateLeft8(mask, j * 2)) == MainWindow.rotateLeft8(pattern, j * 2)) {
												//check if it curves back
												int farx = x + dx[j*2]*2;
												int fary = y + dy[j*2]*2;

												int farx2 = farx + dx[(j * 2 + 2) % 8];
												int fary2 = fary + dy[(j * 2 + 2) % 8];

												int farx3 = farx + dx[(j * 2 + 2) % 8] * 2;
												int fary3 = fary + dy[(j * 2 + 2) % 8] * 2;

												boolean far1Blocked = farx < 0 || farx >= width || fary < 0 || fary >= height || overlays[farx][fary] == 1;
												boolean far2Blocked = farx2 < 0 || farx2 >= width || fary2 < 0 || fary2 >= height || overlays[farx2][fary2] == 1;
												boolean far3Blocked = farx3 < 0 || farx3 >= width || fary3 < 0 || fary3 >= height || overlays[farx3][fary3] == 1;

												if (!far1Blocked && (far2Blocked || far3Blocked)) {
													rotation = (j) % 4;
													//big piece
													changedTiles.add(new TileData(plane, xcoord, ycoord, overlayid, shape, rotation));
//													client.sceneGraph.getMapRegion().overlayIds[plane][xcoord][ycoord] = (byte) overlayid;
//													client.sceneGraph.getMapRegion().overlayShape[plane][xcoord][ycoord] = (byte) shape;
//													client.sceneGraph.getMapRegion().overlayRotation[plane][xcoord][ycoord] = (byte) rotation;
													//little piece
													int xcoord2 = xcoord + dx[j*2];
													int ycoord2 = ycoord + dy[j*2];
//													client.sceneGraph.getMapRegion().overlayIds[plane][xcoord2][ycoord2] = (byte) overlayid;
//													client.sceneGraph.getMapRegion().overlayShape[plane][xcoord2][ycoord2] = (byte) 2; //little triangle
//													client.sceneGraph.getMapRegion().overlayRotation[plane][xcoord2][ycoord2] = (byte) ((rotation + 2) % 4);

													changedTiles.add(new MainWindow.TileData(plane, xcoord2, ycoord2, overlayid, 2, ((rotation + 2) % 4)));

													//top of the L. so corner flattener doesn't interfere
//													int xcoord3 = xcoord + dx[j*2+1];
//													int ycoord3 = ycoord + dy[j*2+1];
//													overlays[x+dx[j*2+1]][y+dy[j*2+1]] = -1;
//													client.sceneGraph.getMapRegion().overlayIds[plane][xcoord3][ycoord3] = (byte) overlayid;
//													client.sceneGraph.getMapRegion().overlayShape[plane][xcoord3][ycoord3] = (byte) 0; //square
//													client.sceneGraph.getMapRegion().overlayRotation[plane][xcoord3][ycoord3] = (byte) 0;
													continue big_loop;

												}


											}
										}
									}

									//add long diagonal. L orientation
									pattern = 0b1101_0000;
									mask = 0b1101_1111;
									shape = 5;
									if (overlays[x][y] == 0) {
										for (int j = 0; j < 4; j++) {
											if ((surroundingTiles & MainWindow.rotateLeft8(mask, j * 2)) == MainWindow.rotateLeft8(pattern, j * 2)) {
												//check if it curves back
												int farx = x + dx[j*2]*2;
												int fary = y + dy[j*2]*2;

												int farx2 = farx - dx[(j * 2 + 2) % 8];
												int fary2 = fary - dy[(j * 2 + 2) % 8];

												int farx3 = farx - dx[(j * 2 + 2) % 8] * 2;
												int fary3 = fary - dy[(j * 2 + 2) % 8] * 2;

												boolean far1Blocked = farx < 0 || farx >= width || fary < 0 || fary >= height || overlays[farx][fary] == 1;
												boolean far2Blocked = farx2 < 0 || farx2 >= width || fary2 < 0 || fary2 >= height || overlays[farx2][fary2] == 1;
												boolean far3Blocked = farx3 < 0 || farx3 >= width || fary3 < 0 || fary3 >= height || overlays[farx3][fary3] == 1;

												if (!far1Blocked && (far2Blocked || far3Blocked)) {
														rotation = (j) % 4;
														//big piece
//														client.sceneGraph.getMapRegion().overlayIds[plane][xcoord][ycoord] = (byte) overlayid;
//														client.sceneGraph.getMapRegion().overlayShape[plane][xcoord][ycoord] = (byte) shape;
//														client.sceneGraph.getMapRegion().overlayRotation[plane][xcoord][ycoord] = (byte) rotation;
													changedTiles.add(new MainWindow.TileData(plane, xcoord, ycoord, overlayid, shape, rotation));

														//little piece
														int xcoord2 = xcoord + dx[j*2];
														int ycoord2 = ycoord + dy[j*2];
//														client.sceneGraph.getMapRegion().overlayIds[plane][xcoord2][ycoord2] = (byte) overlayid;
//														client.sceneGraph.getMapRegion().overlayShape[plane][xcoord2][ycoord2] = (byte) 3; //little triangle
//														client.sceneGraph.getMapRegion().overlayRotation[plane][xcoord2][ycoord2] = (byte) ((rotation + 2) % 4);
													changedTiles.add(new MainWindow.TileData(plane, xcoord2, ycoord2, overlayid, 3, ((rotation + 2) % 4)));
														//top of the L. so corner flattener doesn't interfere
//														int xcoord3 = xcoord - dx[j*2+3];
//														int ycoord3 = ycoord - dy[j*2+3];
//														overlays[x-dx[j*2+1]][y-dy[j*2+1]] = -1;
//														client.sceneGraph.getMapRegion().overlayIds[plane][xcoord3][ycoord3] = (byte) overlayid;
//														client.sceneGraph.getMapRegion().overlayShape[plane][xcoord3][ycoord3] = (byte) 0; //square
//														client.sceneGraph.getMapRegion().overlayRotation[plane][xcoord3][ycoord3] = (byte) 0;
														continue big_loop;
												}


											}
										}
									}

									//flatten corner pieces
									pattern = 0b0000_0101;
									mask = 0b1111_1101;
									shape = 1;
									if (overlays[x][y] == 1) {
										for (int j = 0; j < 4; j++) {
											if ((surroundingTiles & MainWindow.rotateLeft8(mask, j * 2)) == MainWindow.rotateLeft8(pattern, j * 2)) {
												rotation = (2+ j) % 4;
												break check_surrounding;
											}
										}
									}

									//add corner piece inbetween diagonal
									pattern = 0b0000_0101;
									mask = 0b0111_0101;
									shape = 1;
									if (overlays[x][y] == 0) {
										for (int j = 0; j < 4; j++) {
											if ((surroundingTiles & MainWindow.rotateLeft8(mask, j * 2)) == MainWindow.rotateLeft8(pattern, j * 2)) {
												rotation = (2 + j) % 4;
												break check_surrounding;

											}
										}
									}

                                        continue big_loop;
                                }
								//System.out.println("overlayid=" + overlayid);
//                                client.sceneGraph.getMapRegion().overlayIds[plane][xcoord][ycoord] = (byte) overlayid;
//                                client.sceneGraph.getMapRegion().overlayShape[plane][xcoord][ycoord] = (byte) shape;
//                                client.sceneGraph.getMapRegion().overlayRotation[plane][xcoord][ycoord] = (byte) rotation;
								changedTiles.add(new MainWindow.TileData(plane, xcoord, ycoord, overlayid, shape, rotation));
                            }
					}
				}

				//make changes, undoable

				if (client.sceneGraph.currentStateCorrect()) {
					client.sceneGraph.initChanges();
				}

				for (TileData tile: changedTiles) {
					int x = tile.x;
					int y = tile.y;
					int shape = tile.shape;
					int rotation = tile.rotation;

					if (SceneGraph.currentState.isPresent()) {
						OverlayState tileState = new OverlayState(x, y, plane);
						tileState.preserve();
						((TileChange<OverlayState>) SceneGraph.currentState.get()).preserveTileState(tileState);
					}

					client.sceneGraph.getMapRegion().overlayIds[plane][x][y] = (byte) overlayid;
					client.sceneGraph.getMapRegion().overlayShape[plane][x][y] = (byte) shape;
					client.sceneGraph.getMapRegion().overlayRotation[plane][x][y] = (byte) rotation;

					client.sceneGraph.tiles[plane][x][y].hasUpdated = true;
				}

				client.sceneGraph.getMapRegion().updateTiles();

				SceneGraph.commitChanges();
				//reload the map
//				int positionX = clientInstance.xCameraPos;
//				int positionY = clientInstance.yCameraPos;
//
//				byte[] packData = MultiMapEncoder.encode(Lists.newArrayList(clientInstance.chunks));
//				Client.runLater.add(() ->{
//					clientInstance.loadChunks(MultiMapEncoder.decode(packData));
//					fullMapView.resizeMap();
//					clientInstance.xCameraPos = positionX;
//					clientInstance.yCameraPos = positionY;
			});

			controller.generateWallsBtn.setOnAction(evt -> {
//				try {
					//load swatch from file
					if(!Client.gameLoaded.get()) {
						FXDialogs.showError(MainWindow.getSingleton().getStage().getOwner(),"Error", "Please wait until the plugin has fully loaded before doing this!");
						return;
					}

					Client client = Client.getSingleton();
					List<SceneTile> selectedTiles = client.sceneGraph.getSelectedTiles();
					int size = selectedTiles.size();
					if (size <= 0) {
						FXDialogs.showError(MainWindow.getSingleton().getStage().getOwner(),"Error", "No tiles selected");
						return;
					}

					List<ObjectDataset> dataset = null;
					try {
						File file = RetentionFileChooser.showOpenDialog(FilterMode.SWATCH);
						ObjectMapper mapper = JsonUtil.getDefaultMapper();
						// mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
						dataset = mapper.readValue(file, new TypeReference<List<ObjectDataset>>() {
						});
					} catch (IOException e) {
						FXDialogs.showError(MainWindow.getSingleton().getStage().getOwner(),"Error", "IO error");
						return;
					}
					if (dataset.size() != 3) {
						FXDialogs.showError(MainWindow.getSingleton().getStage().getOwner(),"Error", "swatch should be 3 objects");
						return;
					}
					//ObjectPreviewWindow.instance.loadToSwatches(dataset);

					//get input for 'inward' value
//					String value = FXDialogs.showTextInput(MainWindow.getSingleton().getStage(), "Generate Walls", "Choose inward:0 or outward:1", "0");
//					if (value == null || !(value.equals("0") || value.equals("1"))) {
//						throw new Exception("input must be 0 or 1");
//					}
//					boolean inward = value.equals("0");

					int id = dataset.get(0).getId();
					if (id != dataset.get(1).getId() || id != dataset.get(2).getId()) {
						FXDialogs.showError(MainWindow.getSingleton().getStage().getOwner(),"Error", "Walls must be the same id (for now...)");
						return;
					}
					SceneTile startTile = selectedTiles.get(0);
					SceneTile endTile = selectedTiles.get(size-1);

					int height = Math.abs(startTile.positionY - endTile.positionY) + 1;
					int width = Math.abs(startTile.positionX - endTile.positionX) + 1;
					//not a catchall for rectangles
					if (size != height * width) {
						FXDialogs.showError(MainWindow.getSingleton().getStage().getOwner(),"Error", "Rectangle please");
						return;
					}

					//better all be on one plane
					int plane = startTile.plane;

//					//test
//					client.sceneGraph.addObject(startTile.positionX, startTile.positionY, plane, id, 0, 0, false);
//					client.sceneGraph.addObject(testTile.positionX, testTile.positionY, plane, id, 9, 0, false);

//					client.sceneGraph.addObject(endTile.positionX, endTile.positionY, plane, id, 9, 0, false);


//					int min_x = Integer.MAX_VALUE;
//					int max_x = -1;
//					int min_y = Integer.MAX_VALUE;
//					int max_y = -1;

					//turn into array
					boolean [][] wallMap = new boolean[width][height];
					for (int i = 0; i < size; i++) {
						SceneTile tile = selectedTiles.get(i);

						if (tile.shape == null)
							continue;
						if (tile.shape.getTileType() == 12) {
							wallMap[i / height][i % height] = true;
						}
					}
					//get surrounding tile data
					int[] dx = {0, 1, 1, 1, 0, -1, -1, -1 }; // column offset
					int[] dy = {1, 1, 0, -1, -1, -1, 0, 1 }; // row offset

					for (int x = 0; x < width; x++) {
						for (int y = 0; y < height; y++) {
							if (wallMap[x][y] == false) continue;

							int surroundingTiles = 0;

							for (int i = 0; i < 8; i++) {
								int nx = x + dx[i];
								int ny = y + dy[i];

								boolean neighbor = false;
								if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
									neighbor = wallMap[nx][ny];
								}

								if (neighbor) {
									surroundingTiles |= (1 << i); // set the i-th bit
								}
							}
							//now determine wall shape based off surroundings
							int type = -1;
							int rotation = -1;
							System.out.println((byte)surroundingTiles);
							switch (surroundingTiles & 0xFF) { // ensure unsigned comparison
								//case 0b11111111: type = 10; rotation = 0; break;

								case 0b11111101: type = 1; rotation = 1; break;
								case 0b11110111: type = 1; rotation = 2; break;
								case 0b11011111: type = 1; rotation = 3; break;
								case 0b01111111: type = 1; rotation = 0; break;

								case 0b11110001: type = 0; rotation = 2; break;
								case 0b11110011: type = 0; rotation = 2; break;
								case 0b11111001: type = 0; rotation = 2; break;
								case 0b11000111: type = 0; rotation = 3; break;
								case 0b11001111: type = 0; rotation = 3; break;
								case 0b11100111: type = 0; rotation = 3; break;
								case 0b00011111: type = 0; rotation = 0; break;
								case 0b00111111: type = 0; rotation = 0; break;
								case 0b10011111: type = 0; rotation = 0; break;
								case 0b01111100: type = 0; rotation = 1; break;
								case 0b11111100: type = 0; rotation = 1; break;
								case 0b01111110: type = 0; rotation = 1; break;

								case 0b11111000: type = 9; rotation = 1; break;
								case 0b01111000: type = 9; rotation = 1; break;
								case 0b11110000: type = 9; rotation = 1; break;
								case 0b01110000: type = 9; rotation = 1; break;
								case 0b11100011: type = 9; rotation = 2; break;
								case 0b11100001: type = 9; rotation = 2; break;
								case 0b11000011: type = 9; rotation = 2; break;
								case 0b11000001: type = 9; rotation = 2; break;
								case 0b10001111: type = 9; rotation = 3; break;
								case 0b10000111: type = 9; rotation = 3; break;
								case 0b00001111: type = 9; rotation = 3; break;
								case 0b00000111: type = 9; rotation = 3; break;
								case 0b00111110: type = 9; rotation = 0; break;
								case 0b00011110: type = 9; rotation = 0; break;
								case 0b00111100: type = 9; rotation = 0; break;
								case 0b00011100: type = 9; rotation = 0; break;
							}


							if (type == -1 || rotation == -1) continue;

							SceneTile wallTile = selectedTiles.get(x*height + y);
							client.sceneGraph.addObject(wallTile.positionX, wallTile.positionY, plane, id, type, rotation, false);

						}

					}





//				} catch (Exception e) {
//					FXDialogs.showError(primaryStage,"Error while generating walls!", "Message: " + e.getMessage());
//				}
			});


			objectPreviewWindow = new ObjectPreviewWindow(objectSwatch);
			objectPreviewWindow.start(new Stage());
			
			//ModelPreviewWindow modelPrev = new ModelPreviewWindow();
			//modelPrev.start(new Stage());

			fullMapView = new MultiRegionMapWindow();
			fullMapView.start(new Stage());
			
			
			remappingTool = new RemappingTool();
			remappingTool.start(new Stage());
			
			controller.getShowRemapperBtn().setOnAction(evt -> {
				remappingTool.show();
				if(remappingTool.valid()) {
					remappingTool.doRemap();
				}
			});

			controller.getShowFullMap().setOnAction(evt -> { 
				fullMapView.show();
				
				SceneGraph.minimapUpdate = true;
			});

			controller.getExportTilesBtn().setOnAction(evt -> export.show());

			controller.getShowObjectViewBtn().setOnAction(evt -> objectPreviewWindow.stage.show());


			clientInstance = Client.initialize(controller.getGamePane().widthProperty().intValue(),
					controller.getGamePane().heightProperty().intValue());

			clientInstance.loadCache(Paths.get(Config.cacheLocation.get()));

			CanvasPane gamePane = new CanvasPane(clientInstance.getGameCanvas());

			GameKeyListener gameKeyListener = new GameKeyListener(clientInstance);
			clientInstance.getGameCanvas().addEventHandler(MouseEvent.ANY, new GameMouseListener(clientInstance));
			clientInstance.getGameCanvas().addEventHandler(ScrollEvent.ANY, new GameMouseListener(clientInstance));
			clientInstance.getGameCanvas().addEventHandler(KeyEvent.ANY, gameKeyListener);

			clientInstance.fullMapVisible.bind(fullMapView.visibleProperty());
		
			
			if(clientInstance.getCache() != null) {
				if(!clientInstance.getCache().getIndexedFileSystem().is317()) {
					String xteaLocation = Settings.getSetting("xteaLoc", "");
					Consumer<Boolean> pickXTEA = (showError) -> {
						String currentXTEALoc = Settings.getSetting("xteaLoc", "");
						selectXTEA.setLocation(currentXTEALoc);
						selectXTEA.show();
						if(selectXTEA.valid()) {
							XTEAManager.loadFromJSON(new File(selectXTEA.getJsonLocation()));
							Settings.putSetting("xteaLoc", selectXTEA.getJsonLocation());
							log.info("Loaded {} XTEAs", XTEAManager.getMaps().size());
						} else if(showError) {
							FXDialogs.showError(primaryStage,"Error loading XTEAS", "You need to select an XTEA json file otherwise maps may fail to load!");
						}
					};
	
					if(xteaLocation.isEmpty() || !lastCacheLoc.equals(Config.cacheLocation.get())) {
						pickXTEA.accept(true);
					} else {
						XTEAManager.loadFromJSON(new File(xteaLocation));
					}
					
					MenuItem changeXTEALoc = new MenuItem("Change XTEAs");
					changeXTEALoc.setOnAction(evt -> pickXTEA.accept(false));
					controller.getFileMenu().getItems().add(controller.getFileMenu().getItems().size() - 2, changeXTEALoc);
				}
			}
			primaryStage.addEventHandler(KeyEvent.ANY, gameKeyListener);
			primaryStage.focusedProperty().addListener((observable, oldValue, newValue) -> {
				if(!newValue){
					log.info("Lost focus!");
					KeyboardState.reset();
					SceneGraph.setMouseIsDown(false);
					Arrays.fill(clientInstance.keyStatuses, 0);
					//clientInstance.visible = false;
				} else {
					log.info("Gained focus!");
					//clientInstance.visible = true;
				}
			});


			SceneGraph.setMouseIsDown(true);
			SceneGraph.setMouseIsDown(false);

			clientInstance.visible = true;

			controller.getGamePane().getChildren().add(0, gamePane);
			controller.getMapPane().getChildren().add(new CanvasPane(clientInstance.mapCanvas));
			clientInstance.errorDisplayed.addListener((observable, oldValue, newValue) -> controller.getReturnToLauncher().setVisible(newValue.booleanValue()));
			controller.getReturnToLauncher().setOnAction(evt -> {
				LauncherWindow.getSingleton().getPrimaryStage().show();
				LauncherWindow.getSingleton().populatePlugins();
				singleton = null;
				if (clientInstance != null) {
					try {
						clientInstance.exit();
					} catch(Exception ex) {
						ex.printStackTrace();

					}
				}
				primaryStage.close();
			});
			ContextMenu menu = new ContextMenu();
			menu.autoHideProperty().set(true);
			MenuItem item = new MenuItem("Save to file");
			item.setOnAction(evt -> {
				File f = RetentionFileChooser.showSaveDialog(FilterMode.PNG);
				if(f != null) {
					try {
						System.out.println(f.getAbsolutePath());
						clientInstance.saveMinimapImage(f);
					} catch (Exception e) {
						e.printStackTrace();
						FXDialogs.showError(primaryStage, "Error while loading saving image", "There was a failure while attempting to save\nthe minimap to the selected file.");

					}
				}
			});
			menu.getItems().addAll(item);
			controller.getMapPane().setOnContextMenuRequested(evt -> {
				menu.show(getStage(), evt.getScreenX(), evt.getScreenY());
			});

			controller.getFixHeightsBtn().setOnAction(evt -> {
				String result = FXDialogs.showConfirm(primaryStage, "Are you sure?", "This fix will set all heights on plane 1 and above based on "
						+ "the tile height at z = 0. This may cause a few issues for some tiles you will have to fix yourself. \n\nWould you like to continue?", 
						"Yes", "No");
				if(result.equalsIgnoreCase("Yes")) {
					for(int plane = 1;plane<4;plane++) {
						for(int absX = 0;absX<clientInstance.sceneGraph.width;absX++) {
							for(int absY = 0;absY<clientInstance.sceneGraph.length;absY++) {
								clientInstance.mapRegion.heightMap[plane][absX][absY] = clientInstance.mapRegion.heightMap[plane - 1][absX][absY] - 240;
							}
						}
					}
					//chunk.mapRegion.tileHeights = newHeights;


					clientInstance.sceneGraph.updateHeights(0, 0, clientInstance.sceneGraph.width, clientInstance.sceneGraph.length);
				}
			});
			controller.getForceMapUpdateBtn().setOnAction(evt -> {
				int positionX = clientInstance.xCameraPos;
				int positionY = clientInstance.yCameraPos;
				
				byte[] packData = MultiMapEncoder.encode(Lists.newArrayList(clientInstance.chunks));
				Client.runLater.add(() ->{
					clientInstance.loadChunks(MultiMapEncoder.decode(packData));
					fullMapView.resizeMap();
					clientInstance.xCameraPos = positionX;
					clientInstance.yCameraPos = positionY;
				});
			});
			primaryStage.setOnHiding((we) -> {

				Settings.putSetting("shutdown", true);
				if (clientInstance != null) {
					try {
						clientInstance.exit();
					} catch(Exception ex) {
						ex.printStackTrace();

					}
				}
				if(singleton != null) {
					Platform.exit();
					System.exit(0);
				}
			});

			ChangeListenerUtil.addListener((oldVal, newVal) -> {
				if(Options.currentTool.get() == ToolType.SELECT_OBJECT){
					clientInstance.sceneGraph.rotateSelectedObjects(oldVal - newVal);
				}
				SceneGraph.onCycleEnd.add(() -> Client.getSingleton().sceneGraph.forceMouseInTile());
			}, Options.rotation);

			controller.getCopyTileFlags().setOnAction(evt -> {
				BitFlag flag = clientInstance.sceneGraph.getSelectedFlag();

				controller.getUnwalkableCheck().setSelected(flag.flagged(RenderFlags.BLOCKED_TILE));
				controller.getBridgeCheck().setSelected(flag.flagged(RenderFlags.BRIDGE_TILE));
				controller.getForceLowestCheck().setSelected(flag.flagged(RenderFlags.FORCE_LOWEST_PLANE));
				controller.getDrawOnLowerZCheck().setSelected(flag.flagged(RenderFlags.RENDER_ON_LOWER_Z));
				controller.getDisableRenderCheck().setSelected(flag.flagged(RenderFlags.DISABLE_RENDERING));

			});

			controller.getCopyTileHeights().setOnAction(evt -> {
				int height = clientInstance.sceneGraph.getSelectedHeight();
				System.out.println(height);
				if(height <= 0) {
					controller.getHeightLevelSlider().setValue(-height);
				} else {
					FXDialogs.showError(primaryStage,"Error while loading tile height", "There was a failure while attempting to grab\ntile height from the selected tile.");
				}
			});

			controller.getGetOverlayFromTile().setOnAction(evt -> {
				if(clientInstance.sceneGraph != null) {
					int overlayId = clientInstance.sceneGraph.getSelectedOverlay() & 0xFF;
					int overlayShape = clientInstance.sceneGraph.getSelectedOverlayShape();
					log.info("id {} shape {}", overlayId, overlayShape);
					if(overlayId > 0) {

						overlaySwatch.setOverlayShape(overlayShape + 1);
						overlaySwatch.selectByOverlay(overlayId - 1);
					}
				}
			});

			controller.getGetUnderlayFromTile().setOnAction(evt -> {
				if(clientInstance.sceneGraph != null) {
					int underlayId = clientInstance.sceneGraph.getSelectedUnderlay();
					if(underlayId > 0) {
						underlaySwatch.selectByUnderlay(underlayId - 1);
					}
				}
			});



			controller.getImportTilesBtn().setOnAction(evt -> {
				File f = RetentionFileChooser.showOpenDialog(primaryStage, FilterMode.JMAP);
				if (f != null) {
					try {
						clientInstance.sceneGraph.importSelection(f);
					} catch (IOException e) {
						e.printStackTrace();
						FXDialogs.showError(primaryStage,"Error while loading prefab!",
								"There was an error while reading the selected file.");
					} catch (Exception e) {
						e.printStackTrace();
						FXDialogs.showError(primaryStage,"Error while parsing prefab!",
								"There was an error while parsing the selected file.");
					}
				}
			});



			ChangeListenerUtil.addListener(() -> {
				SceneGraph.onCycleEnd.add(() -> {
					Client.updateChunkTiles();
					SceneGraph.minimapUpdate = true;
				});
			}, Options.showHiddenTiles);

			ChangeListenerUtil.addListener(() -> {
				SceneGraph.onCycleEnd.add(() -> {
					Client.updateChunkTiles();
					Client.getSingleton().sceneGraph.resetTiles();
					SceneGraph.minimapUpdate = true;
				});
			}, Options.currentHeight);



			ApplicationPluginLoader.loadPlugins(this);
			ChangeListenerUtil.addListener(() -> {
				if(Client.gameLoaded.get()) {
					underlaySwatch.clear();
					overlaySwatch.clear();
					fillSwatches();
				}
			}, Options.hdTextures);
			
			this.setupOpenOptions();
			this.setupSaveOptions();

			final boolean reloadSaved = loadAutosave;

			ChangeListenerUtil.addListener(true, () -> {
				fillSwatches();

				try {
					byte[] modelData = ByteStreams.toByteArray(getClass().getResourceAsStream("/misc/mapfunction.dat"));

					MeshLoader.getSingleton().load(modelData, 111);
					
					
				} catch(Exception ex) {
					ex.printStackTrace();
				}
				

				if(reloadSaved) {
					File landscapeFile = new File(autosavePath, "autosave.pack");
					if(landscapeFile.exists()) {
						try {
							byte[] landscapeData = Files.readAllBytes(landscapeFile.toPath());

							final byte[] fLandscape = landscapeData;
							Client.runLater.add(() -> {
								clientInstance.loadChunks(MultiMapEncoder.decode(fLandscape));
								fullMapView.resizeMap();
							});//TODO
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
							FXDialogs.showError(primaryStage,"Error while loading map!", "There was an error while loading or parsing the autosave data.");
						}
					}
				}
				Platform.runLater(() -> {
					objectPreviewWindow.fillList();
				});
				try {
					setupAutoSave();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}, Client.gameLoaded);

			EventBus.getDefault().register(this);
		} catch (Exception e) {
			e.printStackTrace();
		}
		primaryStage.sizeToScene();
	}
	
	private static ScheduledExecutorService service = Executors.newScheduledThreadPool(4);
	private void setupAutoSave(){

		int autosaveSeconds = Settings.getSetting("autosaveSeconds", 60);
		Settings.putSetting("autosaveSeconds", autosaveSeconds);

		service.scheduleAtFixedRate(() -> AutoSaveJob.execute(clientInstance), 5, 5, TimeUnit.MINUTES);
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	public void onStatusUpdate(StatusUpdate update) {
		//Platform.runLater(() -> controller.getStatusLabel().setText(update.getText()));
	}

	public void setupSaveOptions() {

		controller.getSaveAsPackFile().setOnAction(act -> {

			File landscapeFile = RetentionFileChooser.showSaveDialog("Enter a name for packed maps file...", stage, "",
					FilterMode.PACK);
			if (landscapeFile == null)
				return;

			byte[] tileMap = MultiMapEncoder.encode(Lists.newArrayList(clientInstance.chunks));

			try {
				Files.write(landscapeFile.toPath(), tileMap);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				FXDialogs.showError(stage,"Error while saving map!", "There was an error while writing packed maps file.");
			}
		});

		controller.getSaveAsJm2File().setOnAction(act -> {
			int startX = clientInstance.xCameraPos;
			int startY = clientInstance.yCameraPos;

			String section = "";
			//ArrayList<Chunk> test = new ArrayList<>();
			//test.add(clientInsance.chunks.get(40));
//			for(Chunk chunk : clientInstance.chunks) {
//			for(Chunk chunk : test) {
//			Chunk chunk = clientInstance.chunks.get(42);
			String chunkid = FXDialogs.showTextInput(MainWindow.getSingleton().getStage(), "Save Chunk",  "Which chunk?", "");
			Chunk chunk = clientInstance.chunks.get(Integer.valueOf(chunkid));
				clientInstance.xCameraPos = (chunk.offsetX + 32) * 128;
				clientInstance.yCameraPos = (chunk.offsetY + 32) * 128;

				File landscapeFile = RetentionFileChooser.showSaveDialog("Enter a name for tiles...", stage, "m" + chunk.regionX + "_" + chunk.regionY, FilterMode.JM2);
				if (landscapeFile == null)
					return;

				byte[] objectMap = clientInstance.sceneGraph.saveObjects(chunk);
				byte[] tileMap = chunk.mapRegion.save_terrain_block(chunk);

				MapRegion land = chunk.mapRegionJm2;
//				land.unpackTilesForJM2Format(tileMap, chunk.offsetX, chunk.offsetY, chunk.regionX, chunk.regionY);
				land.unpackTilesForJM2Format(tileMap, 0, 0, chunk.regionX, chunk.regionY);

				section += "==== MAP ====\n";

				for (int level = 0; level < 4; level++) {
					for (int x = 0; x < 64; x++) {
						for (int z = 0; z < 64; z++) {
							String str = "";

							if (land.heightMap[level][x][z] != -1) {
								str += "h" + land.heightMap[level][x][z] + " ";
							}

							if (land.overlayIds[level][x][z] != -1 && land.overlayIds[level][x][z] != 0) {
								//convert to unsigned
								int overlayid = land.overlayIds[level][x][z] & 0xFF;
								System.out.println(land.overlayIds[level][x][z] + " converted to: " + overlayid);
								if (land.overlayShape[level][x][z] != -1 && land.overlayShape[level][x][z] != 0 && land.overlayRotation[level][x][z] != -1 && land.overlayRotation[level][x][z] != 0) {
									//		`o${land.overlayIds[level][x][z]};${land.overlayShape[level][x][z]};${land.overlayRotation[level][x][z]} `;
									str += "o" + overlayid + ";" + land.overlayShape[level][x][z] + ";"  + land.overlayRotation[level][x][z] + " ";;
								} else if (land.overlayShape[level][x][z] != -1 && land.overlayShape[level][x][z] != 0) {
									//		`o${land.overlayIds[level][x][z]};${land.overlayShape[level][x][z]} `;
									str += "o" + overlayid + ";" + land.overlayShape[level][x][z] + " ";
								} else {
									//		`o${land.overlayIds[level][x][z]} `;
									str += "o" + overlayid + " ";;
								}
							}

							if (land.flags[level][x][z] != -1) {
								str += "f" + land.flags[level][x][z] + " ";
							}

							if (land.underlay[level][x][z] != -1) {
//							if (land.underlay[level][x][z] > -1) {
								int underlayid = land.underlay[level][x][z] & 0xFF;
								str += "u" + underlayid + " ";
							}

							if (!str.isEmpty()) {
								section += level + " " + x + " " + z + ": " + str.trim() + "\n";
							}
						}
					}
				}

				section += "\n==== LOC ====\n";
//				section += chunk.mapRegion.unpackObjectsPlease(clientInstance.sceneGraph, objectMap, chunk.offsetX, chunk.offsetY);
				section += chunk.mapRegion.unpackObjectsPlease(clientInstance.sceneGraph, objectMap, 0, 0);
				try {
					Files.write(landscapeFile.toPath(), section.getBytes(StandardCharsets.UTF_8));
					land.unpackTiles(tileMap, chunk.offsetX, chunk.offsetY, chunk.regionX, chunk.regionY);

				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					FXDialogs.showError(stage,"Error while saving map!", "There was an error while writing map file.");
				}

//			}
			clientInstance.xCameraPos = startX;
			clientInstance.yCameraPos = startY;
		});

		controller.getSaveMenuItem().setOnAction(act -> {
			int startX = clientInstance.xCameraPos;
			int startY = clientInstance.yCameraPos;

			for(Chunk chunk : clientInstance.chunks) {
				clientInstance.xCameraPos = (chunk.offsetX + 32) * 128;
				clientInstance.yCameraPos = (chunk.offsetY + 32) * 128;
				File landscapeFile = RetentionFileChooser.showSaveDialog("Enter a name for tiles...", stage, chunk.tileMapId + "",
						FilterMode.DAT, FilterMode.GZIP);
				if (landscapeFile == null)
					return;
				File objectFile = RetentionFileChooser.showSaveDialog("Enter a name for objects...", stage, chunk.objectMapId + "",
						FilterMode.DAT, FilterMode.GZIP);

				if (objectFile == null)
					return;

				byte[] objectMap = clientInstance.sceneGraph.saveObjects(chunk);
				byte[] tileMap = chunk.mapRegion.save_terrain_block(chunk);

				if (landscapeFile.getName().endsWith(".gz")) {
					try {
						tileMap = GZIPUtils.gzipBytes(tileMap);
						if(tileMap == null)
							throw new IOException("GZIP error");
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
						FXDialogs.showError(stage,"Error while saving map!",
								"There was an error while writing map file.");
						return;
					}
				}
				if (objectFile.getName().endsWith(".gz")) {
					try {
						objectMap = GZIPUtils.gzipBytes(objectMap);
						if(objectMap == null)
							throw new IOException("GZIP error");
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
						FXDialogs.showError(stage,"Error while saving map!",
								"There was an error while writing map file.");
						return;
					}
				}

				try {

					Files.write(objectFile.toPath(), objectMap);
					Files.write(landscapeFile.toPath(), tileMap);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					FXDialogs.showError(stage,"Error while saving map!", "There was an error while writing map file.");
				}

			}
			clientInstance.xCameraPos = startX;
			clientInstance.yCameraPos = startY;
		});
	}

	public void setupOpenOptions() throws Exception {

		GenerateNewMapWindow genNew = new GenerateNewMapWindow();
		
		genNew.start(new Stage());
	
		controller.getNewMapButton().setOnAction(evt -> {
			/*try {
				byte[] landscape = ByteStreams.toByteArray(getClass().getResourceAsStream("/misc/blank_region.dat"));
				byte[] object = ByteStreams.toByteArray(getClass().getResourceAsStream("/misc/blank_regionO.dat"));

				Client.runLater.add(() -> {
					clientInstance.loadFiles(landscape, object, 0, 0);
					fullMapView.resizeMap();
				});
			} catch(Exception ex) {
				FXDialogs.showError("Error while creating new map", "There was a failure while attempting to initialize\na new map.");
				ex.printStackTrace();
			}*/
			
			genNew.show();
			if(genNew.okClicked) {
				int chunkWidth = genNew.getWidth();
				int chunkHeight = genNew.getLength();
				try {

					Client.runLater.add(() -> {
						clientInstance.loadNew(chunkWidth, chunkHeight, genNew.getHeights());
						fullMapView.resizeMap();
					});
				} catch(Exception ex) {
					FXDialogs.showError(stage,"Error while creating new map", "There was a failure while attempting to initialize\na new map.");
					ex.printStackTrace();
				}
			}
		
		});

		controller.getOpenAsPackBtn().setOnAction(act -> {

			selectPack.show();

			if(!selectPack.valid())
				return;

			File packFile = new File(selectPack.getPackText());


			try {
				final byte[] packData = Files.readAllBytes(packFile.toPath());


				Client.runLater.add(() ->{
					clientInstance.loadChunks(MultiMapEncoder.decode(packData));
					fullMapView.resizeMap();
				});
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				FXDialogs.showError(stage,"Error while loading map!",
						"There was an error while loading or parsing the selected file.");
			}

		});



		controller.getOpenFileButton().setOnAction(act -> {

			selectFiles.show();

			if(!selectFiles.valid())
				return;
			
			Client.runLater.add(() -> {
				clientInstance.loadChunks(selectFiles.prepareChunks());
				fullMapView.resizeMap();
			});

		});

		controller.getOpenHashButton().setOnAction(evt -> {

			pickHash.show();
			if(!pickHash.valid())
				return;
			int hash = pickHash.getHash();
			int width = pickHash.getWidth();
			int length = pickHash.getLength();
			Client.runLater.add(() -> { 
				clientInstance.loadCoordinates((hash >> 8) * 64, (hash & 0xff) * 64, width, length);
				fullMapView.resizeMap();
			});
		});

		controller.getOpenCoordinateButton().setOnAction(evt -> {
			/*String value = FXDialogs.showTextInput("Load from coordinates", "Please enter the regions coordinates in the format x,y: ", "");
			if(value != null && !value.equals("")) {
				String[] split = value.replaceAll(" ", "").split(",");
				int x = Integer.valueOf(split[0]);
				int y = Integer.valueOf(split[1]);
				x /= 64;
				y /= 64;
				int hash = (x << 0x39b8d2e8) + y;
				Client.runLater.add(() -> clientInstance.loadCoordinates((hash >> 8) * 64, (hash & 0xff) * 64, 1, 1));
			}*/

			pickCoords.show();
			if(!pickCoords.valid())
				return;
			int x = pickCoords.getXCoordinate();
			int y = pickCoords.getYCoordinate();	
			x /= 64;
			y /= 64;
			int hash = (x << 8) + y;
			int width = pickCoords.getWidth();
			int length = pickCoords.getLength();
			Client.runLater.add(() -> { 
				clientInstance.loadCoordinates((hash >> 8) * 64, (hash & 0xff) * 64, width, length);
				fullMapView.resizeMap();
			});

		});
	}

	public static MainWindow getSingleton() {
		return singleton;
	}

}
