package dbis.stark.visualization;

import dbis.stark.raster.Tile;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import dbis.stark.STObject;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import scala.Tuple2;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Collections;
import java.util.stream.StreamSupport;

public class Visualization implements Serializable {
    /**
     *
     */
    private static final long serialVersionUID = 1L;
    private double scaleX, scaleY;
    private int imageWidth, imageHeight;
    private int pixelColor = new Color(255, 0, 0).getRGB();
    private boolean fillPolygon;
    private boolean flipImageVert;
    private boolean worldProj;

    public <G extends STObject, T> boolean visualize(JavaSparkContext sparkContext, JavaRDD<Tuple2<G, T>> rdd,
                                                     int imageWidth, int imageHeight, Envelope envelope,
                                                     boolean flipImageVert, String outputPath, String outputType) {

        return visualize(sparkContext, rdd, imageWidth, imageHeight, envelope, flipImageVert, outputPath, outputType, null, 1, false, false);
    }

    public <G extends STObject, T> boolean visualize(JavaSparkContext sparkContext, JavaRDD<Tuple2<G, T>> rdd,
                                                     int imageWidth, int imageHeight, Envelope envelope,
                                                     boolean flipImageVert, String outputPath, String outputType,
                                                     String bgImagePath, int pointSize, boolean fillPolygon, boolean worldProj) {

        this.imageHeight = imageHeight;
        this.imageWidth = imageWidth;
        this.flipImageVert = flipImageVert;
        this.fillPolygon = fillPolygon;
        this.worldProj = worldProj;

        scaleX = (double) imageWidth / envelope.getWidth();
        scaleY = (double) imageHeight / envelope.getHeight();

        Broadcast<Envelope> env = sparkContext.broadcast(envelope);

        return draw(rdd, env, outputPath, outputType, bgImagePath, pointSize);
    }

    public boolean visualize(JavaSparkContext sparkContext, JavaRDD<Tile<Integer>> rdd, int imageWidth, int imageHeight, Envelope envelope, String outputPath) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;

        Broadcast<Envelope> env = sparkContext.broadcast(envelope);

        this.flipImageVert = false;

        this.scaleX = this.imageWidth / envelope.getWidth();
        this.scaleY = this.imageHeight / envelope.getHeight();

        return drawRaster(rdd, env, outputPath);
    }

    private <G extends STObject,T> boolean draw(JavaRDD<Tuple2<G, T>> rdd, Broadcast<Envelope> env, String outputPath, String outputType, String bgImagePath, int pointSize) {
        BufferedImage finalImage = rdd.mapPartitions(iter -> {
            BufferedImage imagePartition = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D imageGraphic = imagePartition.createGraphics();
            imageGraphic.setColor(new Color(pixelColor));
            Envelope envelope = env.value();

            Iterable<Tuple2<G,T>> iterable = () -> iter;

            StreamSupport.stream(iterable.spliterator(),false) // make iterator a stream (in Java iterator does not support map function
                .forEach(tuple -> { // draw every tuple
                    try {
                        drawSTObject(tuple._1, imageGraphic, envelope, pointSize);
                    } catch(Exception ignored) { } // in case of an error just ignore
                });
            return Collections.singletonList(new ImageSerializableWrapper(imagePartition)).iterator();

        })
        .reduce((v1,v2) -> { // merge the RDD of images into a single image
            BufferedImage combinedImage = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics graphics = combinedImage.getGraphics();
            graphics.drawImage(v1.image, 0, 0, null);
            graphics.drawImage(v2.image, 0, 0, null);
            return new ImageSerializableWrapper(combinedImage);
        })
        .getImage(); // return that image

        if(bgImagePath != null) { // add background image if specified
            BufferedImage bg = null;
            try {
                bg = ImageIO.read(new File(bgImagePath));

                BufferedImage combinedImage = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics graphics = combinedImage.getGraphics();
                graphics.drawImage(bg, 0, 0, null);
                graphics.drawImage(finalImage, 0, 0, null);
                finalImage = combinedImage;
            } catch (IOException e) {}
        }

        return saveImageAsLocalFile(finalImage, outputPath, outputType);
    }

    private boolean drawRaster(JavaRDD<Tile<Integer>> rdd, Broadcast<Envelope> envelope, String outputPath) {

        BufferedImage theImage = rdd.mapPartitions( iter -> {
            BufferedImage img = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            Iterable<Tile<Integer>> iterable = () -> iter;

            StreamSupport.stream(iterable.spliterator(), false)
                .forEach(tile -> {

                    for (int x = 0; x < tile.width(); x++) {
                        for (int y = 0; y < tile.height(); y++) {

                            g.setColor(new Color(tile.value(x,y)));

//                            Coordinate c = new Coordinate(tile.ulx() + x, tile.uly() + y);

//                            Tuple2<Integer, Integer> pos = getImageCoordinates(c, envelope.getValue());

                            Integer imgX = (int)(tile.ulx() + x);
                            Integer imgY = (int)(tile.uly() - (tile.height() - y));
                            Tuple2<Integer, Integer> pos = new Tuple2<>(imgX, imgY);


//                            System.out.printf("%d , %d  has img coords: %s\n", x,y, pos.toString());

                            if(pos != null)
                                drawPoint(g, pos, 5);

                        }
                    }

                });

            return Collections.singletonList(new ImageSerializableWrapper(img)).iterator();
        }).reduce((img1, img2) -> {
            BufferedImage combinedImage = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics graphics = combinedImage.getGraphics();
            graphics.drawImage(img1.image, 0, 0, null);
            graphics.drawImage(img2.image, 0, 0, null);
            return new ImageSerializableWrapper(combinedImage);
        })
        .image;

        return saveImageAsLocalFile(theImage, outputPath, "png");
    }

    private boolean saveImageAsLocalFile(BufferedImage finalImage, String outputPath, String outputType) {
        File outputImage = new File(outputPath + "." + outputType);
        System.out.println("write to file: "+outputImage);
//		outputImage.getParentFile().mkdirs();
        try {
            ImageIO.write(finalImage, outputType, outputImage);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

    private void drawSTObject(STObject obj, Graphics2D g, Envelope env, int pointSize) throws Exception {
        Geometry geo = obj.getGeo();

        if(geo instanceof Point) {
            Tuple2<Integer, Integer> p = null;
            if(worldProj) p = getMercatorProjection(geo.getCoordinate(), env);
            else p = getImageCoordinates(geo.getCoordinate(), env);
            if(p != null) drawPoint(g, p, pointSize);
        } else if(geo instanceof Polygon) {
            Coordinate[] coordinates = geo.getCoordinates();
            java.awt.Polygon poly = new java.awt.Polygon();
            for(Coordinate c : coordinates) {
                Tuple2<Integer, Integer> p = null;
                if(worldProj) p = getMercatorProjection(c, env);
                else p = getImageCoordinates(c, env);
                if(p != null) poly.addPoint(p._1, p._2);
            }

            if(fillPolygon)	g.fillPolygon(poly);
            else g.drawPolygon(poly);
        } else {
            throw new Exception("Unsupported spatial object type. Only supports: Point, Polygon!");
        }
    }

    private void drawPoint(Graphics2D g, Tuple2<Integer, Integer> p, int pointSize) {
        g.fillRect(p._1, p._2, pointSize, pointSize);
    }

    private Tuple2<Integer, Integer> getMercatorProjection(Coordinate p, Envelope envelope) {
        double lat = p.y;
        double lng = p.x;

        //values for mercator map
        double mapLatBottom = -82.05;
        double mapLngRight = 180;
        double mapLngLeft = -180.85;

        double mapLatBottomRad = mapLatBottom * Math.PI / 180;
        double latitudeRad = lat * Math.PI / 180;
        double mapLngDelta = (mapLngRight - mapLngLeft);

        double worldMapWidth = ((imageWidth / mapLngDelta) * 360) / (2 * Math.PI);
        double mapOffsetY = (worldMapWidth / 2 * Math.log((1 + Math.sin(mapLatBottomRad)) / (1 - Math.sin(mapLatBottomRad))));

        double x = (lng - mapLngLeft) * (imageWidth / mapLngDelta);
        double y = imageHeight - ((worldMapWidth / 2 * Math.log((1 + Math.sin(latitudeRad)) / (1 - Math.sin(latitudeRad)))) - mapOffsetY);

        return new Tuple2<Integer, Integer>((int) x, (int) y);
    }

    private Tuple2<Integer, Integer> getImageCoordinates(Coordinate p, Envelope envelope) {
        double x = p.x;
        double y = p.y;

        if(!envelope.contains(x, y)) return null;

        if(flipImageVert) {
            y = envelope.centre().y - (y - envelope.centre().y);
        }

        int resultX = (int) ((x - envelope.getMinX()) * scaleX);
        int resultY = (int) ((y - envelope.getMinY()) * scaleY);

        return new Tuple2<>(resultX, resultY);
    }

    class ImageSerializableWrapper implements Serializable {

        /**
         *
         */
        private static final long serialVersionUID = 1L;

        transient BufferedImage image;

        ImageSerializableWrapper(BufferedImage image) {
            this.image = image;
        }

        // Serialization method.
        private void writeObject(ObjectOutputStream out) throws IOException {
            out.defaultWriteObject();
            ImageIO.write(image, "png", out);
        }

        // Deserialization method.
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            image = ImageIO.read(in);
        }

        BufferedImage getImage() {
            return this.image;
        }
    }
}