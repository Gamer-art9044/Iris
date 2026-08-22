/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.engine.framework.render;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeGeneratorLink;
import art.arcane.iris.util.project.interpolation.IrisInterpolation;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.function.BiFunction;

public class IrisRenderer {
    private static final int BLUE = Color.BLUE.getRGB();
    private static final int YELLOW = Color.YELLOW.getRGB();
    private static final int GREEN = Color.GREEN.getRGB();

    private final Engine renderer;

    public IrisRenderer(Engine renderer) {
        this.renderer = renderer;
    }

    public BufferedImage render(double sx, double sz, double size, int resolution, RenderType currentType) {
        BufferedImage image = new BufferedImage(resolution, resolution, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        BiFunction<Double, Double, Integer> colorFunction = (d, dx) -> 0;

        switch (currentType) {
            case BIOME, DECORATOR_LOAD, OBJECT_LOAD, LAYER_LOAD ->
                    colorFunction = (x, z) -> renderer.getComplex().getTrueBiomeStream().get(x, z).getColor(renderer, currentType).getRGB();
            case BIOME_LAND ->
                    colorFunction = (x, z) -> renderer.getComplex().getLandBiomeStream().get(x, z).getColor(renderer, currentType).getRGB();
            case BIOME_SEA ->
                    colorFunction = (x, z) -> renderer.getComplex().getSeaBiomeStream().get(x, z).getColor(renderer, currentType).getRGB();
            case REGION ->
                    colorFunction = (x, z) -> renderer.getComplex().getRegionStream().get(x, z).getColor(renderer.getComplex(), currentType).getRGB();
            case CAVE_LAND ->
                    colorFunction = (x, z) -> renderer.getComplex().getCaveBiomeStream().get(x, z).getColor(renderer, currentType).getRGB();
            case HEIGHT ->
                    colorFunction = (x, z) -> Color.getHSBColor(renderer.getComplex().getHeightStream().get(x, z).floatValue(), 1f, 1f).getRGB();
            case CONTINENT -> colorFunction = (x, z) -> {
                IrisBiome b = renderer.getBiome((int) Math.round(x), renderer.getMaxHeight() - 1, (int) Math.round(z));
                IrisBiomeGeneratorLink g = b.getGenerators().get(0);
                if (g.getMax() <= 0) return BLUE;
                if (g.getMin() < 0) return YELLOW;
                return GREEN;
            };
        }

        double x, z;
        for (int i = 0; i < resolution; i++) {
            x = IrisInterpolation.lerp(sx, sx + size, (double) i / (double) resolution);
            for (int j = 0; j < resolution; j++) {
                z = IrisInterpolation.lerp(sz, sz + size, (double) j / (double) resolution);
                pixels[j * resolution + i] = colorFunction.apply(x, z);
            }
        }

        return image;
    }
}
