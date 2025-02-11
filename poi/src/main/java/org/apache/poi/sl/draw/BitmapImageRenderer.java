/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.sl.draw;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Dimension2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.apache.commons.io.input.UnsynchronizedByteArrayInputStream;
import org.apache.commons.io.output.UnsynchronizedByteArrayOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.util.IOUtils;

/**
 * For now this class renders only images supported by the javax.imageio.ImageIO framework.
 **/
public class BitmapImageRenderer implements ImageRenderer {
    private static final Logger LOG = LogManager.getLogger(BitmapImageRenderer.class);

    protected BufferedImage img;
    private boolean doCache;
    private byte[] cachedImage;
    private String cachedContentType;

    @Override
    public boolean canRender(String contentType) {
        PictureType[] pts = {
            PictureType.JPEG, PictureType.PNG, PictureType.BMP, PictureType.GIF
        };
        for (PictureType pt : pts) {
            if (pt.contentType.equalsIgnoreCase(contentType)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void loadImage(InputStream data, String contentType) throws IOException {
        InputStream in = data;
        if (doCache) {
            UnsynchronizedByteArrayOutputStream bos = new UnsynchronizedByteArrayOutputStream();
            IOUtils.copy(data, bos);
            cachedImage = bos.toByteArray();
            cachedContentType = contentType;
            in = bos.toInputStream();
        }
        img = readImage(in, contentType);
    }

    @Override
    public void loadImage(byte[] data, String contentType) throws IOException {
        if (data == null) {
            return;
        }
        if (doCache) {
            cachedImage = data.clone();
            cachedContentType = contentType;
        }
        img = readImage(new UnsynchronizedByteArrayInputStream(data), contentType);
    }

    /**
     * Read the image data via ImageIO and optionally try to workaround metadata errors.
     * The resulting image is of image type {@link BufferedImage#TYPE_INT_ARGB}
     *
     * @param data the data stream
     * @param contentType the content type
     * @return the bufferedImage or null, if there was no image reader for this content type
     * @throws IOException thrown if there was an error while processing the image
     */
    private static BufferedImage readImage(final InputStream data, final String contentType) throws IOException {
        IOException lastException = null;
        BufferedImage img = null;

        final InputStream bis;
        if (data instanceof ByteArrayInputStream) {
            bis = data;
        } else if (data instanceof UnsynchronizedByteArrayInputStream) {
            bis = data;
        } else {
            UnsynchronizedByteArrayOutputStream bos = new UnsynchronizedByteArrayOutputStream(0x3FFFF);
            IOUtils.copy(data, bos);
            bis = bos.toInputStream();
        }


        // currently don't use FileCacheImageInputStream,
        // because of the risk of filling the file handles (see #59166)
        ImageInputStream iis = new MemoryCacheImageInputStream(bis);
        try {
            Iterator<ImageReader> iter = ImageIO.getImageReaders(iis);
            while (img==null && iter.hasNext()) {
                ImageReader reader = iter.next();
                ImageReadParam param = reader.getDefaultReadParam();
                // 0:default mode, 1:fallback mode
                for (int mode=0; img==null && mode<3; mode++) {
                    lastException = null;
                    if (mode > 0) {
                        bis.reset();
                        iis.close();
                        iis = new MemoryCacheImageInputStream(bis);
                    }

                    try {

                        switch (mode) {
                            case 0:
                                reader.setInput(iis, false, true);
                                img = reader.read(0, param);
                                break;
                            case 1: {
                                // try to load picture in gray scale mode
                                // fallback mode for invalid image band metadata
                                // see http://stackoverflow.com/questions/10416378
                                Iterator<ImageTypeSpecifier> imageTypes = reader.getImageTypes(0);
                                while (imageTypes.hasNext()) {
                                    ImageTypeSpecifier imageTypeSpecifier = imageTypes.next();
                                    int bufferedImageType = imageTypeSpecifier.getBufferedImageType();
                                    if (bufferedImageType == BufferedImage.TYPE_BYTE_GRAY) {
                                        param.setDestinationType(imageTypeSpecifier);
                                        break;
                                    }
                                }
                                reader.setInput(iis, false, true);
                                img = reader.read(0, param);
                                break;
                            }
                            case 2: {
                                // try to load truncated pictures by supplying a BufferedImage
                                // and use the processed data up till the point of error
                                reader.setInput(iis, false, true);
                                int height = reader.getHeight(0);
                                int width = reader.getWidth(0);

                                Iterator<ImageTypeSpecifier> imageTypes = reader.getImageTypes(0);
                                if (imageTypes.hasNext()) {
                                    ImageTypeSpecifier imageTypeSpecifier = imageTypes.next();
                                    img = imageTypeSpecifier.createBufferedImage(width, height);
                                    param.setDestination(img);
                                } else {
                                    lastException = new IOException("unable to load even a truncated version of the image.");
                                    break;
                                }

                                try {
                                    reader.read(0, param);
                                } finally {
                                    if (img.getType() != BufferedImage.TYPE_INT_ARGB) {
                                        int y = findTruncatedBlackBox(img, width, height);
                                        if (y < height) {
                                            BufferedImage argbImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                                            Graphics2D g = argbImg.createGraphics();
                                            g.clipRect(0, 0, width, y);
                                            g.drawImage(img, 0, 0, null);
                                            g.dispose();
                                            img.flush();
                                            img = argbImg;
                                        }
                                    }
                                }
                                break;
                            }
                        }

                    } catch (IOException e) {
                        if (mode < 2) {
                            lastException = e;
                        }
                    } catch (RuntimeException e) {
                        if (mode < 2) {
                            lastException = new IOException("ImageIO runtime exception - "+(mode==0 ? "normal" : "fallback"), e);
                        }
                    }
                }
                reader.dispose();
            }
        } finally {
            iis.close();
        }

        // If you don't have an image at the end of all readers
        if (img == null) {
            if (lastException != null) {
                // rethrow exception - be aware that the exception source can be in
                // multiple locations above ...
                throw lastException;
            }
            LOG.atWarn().log("Content-type: {} is not support. Image ignored.", contentType);
            return null;
        }

        // add alpha channel
        if (img.getType() != BufferedImage.TYPE_INT_ARGB) {
            BufferedImage argbImg = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics g = argbImg.getGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            return argbImg;
        }

        return img;
    }

    private static int findTruncatedBlackBox(BufferedImage img, int width, int height) {
        // scan through the image to find the black box after the truncated data
        int h = height-1;
        for (; h > 0; h--) {
            for (int w = width-1; w > 0; w-=width/10) {
                int p = img.getRGB(w, h);
                if (p != 0xff000000) {
                    return h+1;
                }
            }
        }
        return 0;
    }


    @Override
    public BufferedImage getImage() {
        return img;
    }

    @Override
    public BufferedImage getImage(Dimension2D dim) {
        if (img == null) {
            return null;
        }
        double w_old = img.getWidth();
        double h_old = img.getHeight();
        double w_new = dim.getWidth();
        double h_new = dim.getHeight();
        if (w_old == w_new && h_old == h_new) {
            return img;
        }
        BufferedImage scaled = new BufferedImage((int)w_new, (int)h_new, BufferedImage.TYPE_INT_ARGB);
        AffineTransform at = new AffineTransform();
        at.scale(w_new/w_old, h_new/h_old);
        AffineTransformOp scaleOp = new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
        scaleOp.filter(img, scaled);
        return scaled;
    }

    @Override
    public Rectangle2D getBounds() {
        return (img == null)
            ? new Rectangle2D.Double()
            : new Rectangle2D.Double(0, 0, img.getWidth(), img.getHeight());
    }

    @Override
    public void setAlpha(double alpha) {
        img = setAlpha(img, alpha);
    }

    public static BufferedImage setAlpha(BufferedImage image, double alpha) {
        if (image == null) {
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }

        if (alpha == 0) {
            return image;
        }

        float[] scalefactors = {1, 1, 1, (float)alpha};
        float[] offsets = {0,0,0,0};
        RescaleOp op = new RescaleOp(scalefactors, offsets, null);
        return op.filter(image, null);
    }


    @Override
    public boolean drawImage(
        Graphics2D graphics,
        Rectangle2D anchor) {
        return drawImage(graphics, anchor, null);
    }

    @Override
    public boolean drawImage(
        Graphics2D graphics,
        Rectangle2D anchor,
        Insets clip) {
        if (img == null) return false;

        boolean isClipped = true;
        if (clip == null) {
            isClipped = false;
            clip = new Insets(0,0,0,0);
        }

        int iw = img.getWidth();
        int ih = img.getHeight();


        double cw = (100000-clip.left-clip.right) / 100000.0;
        double ch = (100000-clip.top-clip.bottom) / 100000.0;
        double sx = anchor.getWidth()/(iw*cw);
        double sy = anchor.getHeight()/(ih*ch);
        double tx = anchor.getX()-(iw*sx*clip.left/100000.0);
        double ty = anchor.getY()-(ih*sy*clip.top/100000.0);

        AffineTransform at = new AffineTransform(sx, 0, 0, sy, tx, ty) ;

        Shape clipOld = graphics.getClip();
        if (isClipped) {
            graphics.clip(anchor.getBounds2D());
        }
        graphics.drawRenderedImage(img, at);
        graphics.setClip(clipOld);

        return true;
    }

    @Override
    public Rectangle2D getNativeBounds() {
        return new Rectangle2D.Double(0, 0, img.getWidth(), img.getHeight());
    }

    @Override
    public void setCacheInput(boolean enable) {
        doCache = enable;
        if (!enable) {
            cachedContentType = null;
            cachedImage = null;
        }
    }

    @Override
    public byte[] getCachedImage() {
        return cachedImage;
    }

    @Override
    public String getCachedContentType() {
        return cachedContentType;
    }
}
