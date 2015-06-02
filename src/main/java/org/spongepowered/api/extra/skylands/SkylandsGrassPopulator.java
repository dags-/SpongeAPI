/*
 * This file is part of SpongeAPI, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.api.extra.skylands;

import com.flowpowered.math.vector.Vector3i;
import com.flowpowered.noise.module.Module;
import com.flowpowered.noise.module.source.Voronoi;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.data.manipulator.block.ShrubData;
import org.spongepowered.api.data.type.ShrubTypes;
import org.spongepowered.api.util.gen.BiomeBuffer;
import org.spongepowered.api.util.gen.MutableBlockBuffer;
import org.spongepowered.api.world.World;
import org.spongepowered.api.world.gen.GeneratorPopulator;

/**
 * Places tall grass with groups of flowers.
 */
public class SkylandsGrassPopulator implements GeneratorPopulator {

    private static final double GRASS_ODDS = 0.3;
    private static final double DOUBLE_GRASS_ODDS = 0.9;
    private static final double COVERED_GRASS_ODDS = 0.8;
    private static final BlockState TALL_GRASS;
    // the type of flower cells, null means just grass, so it's not all flowers
    @SuppressWarnings("ConstantConditions")
    private static final Flower[] FLOWERS = {
            new Flower(BlockTypes.REDSTONE_BLOCK, false),
            new Flower(BlockTypes.GOLD_BLOCK, false),
            null,
            null,
            null,
            null,
            null,
            null
    };
    private final Voronoi flowerCells = new Voronoi();
    private final Voronoi flowerDensities = new Voronoi();
    private final RarityCurve flowerOdds = new RarityCurve();

    static {
        //noinspection ConstantConditions
        final BlockState defaultGrass = BlockTypes.TALLGRASS.getDefaultState();
        //noinspection ConstantConditions
        TALL_GRASS = defaultGrass.withData(defaultGrass.getManipulator(ShrubData.class).get().setValue(ShrubTypes.TALL_GRASS)).get();
    }

    public SkylandsGrassPopulator() {
        flowerCells.setFrequency(0.1);
        flowerCells.setDisplacement(FLOWERS.length - 1);
        flowerCells.setEnableDistance(false);
        flowerDensities.setFrequency(0.1);
        flowerDensities.setDisplacement(0);
        flowerDensities.setEnableDistance(true);
        flowerOdds.setSourceModule(0, flowerDensities);
        flowerOdds.setDegree(4);
    }

    @Override
    @SuppressWarnings("ConstantConditions")
    public void populate(World world, MutableBlockBuffer buffer, BiomeBuffer biomes) {
        final Vector3i max = buffer.getBlockMax();
        final Vector3i min = buffer.getBlockMin();
        final int yMax = max.getY() - 2;
        final int yMin = min.getY();
        if (yMax < SkylandsTerrainGenerator.MIN_HEIGHT || yMin > SkylandsTerrainGenerator.MAX_HEIGHT) {
            return;
        }
        final long seed = world.getProperties().getSeed();
        final int intSeed = (int) (seed >> 32 ^ seed);
        final int intSeed2 = intSeed * 28703;
        final int yStart = Math.min(yMax, SkylandsTerrainGenerator.MAX_HEIGHT);
        final int yEnd = Math.max(yMin, SkylandsTerrainGenerator.MIN_HEIGHT);
        for (int zz = min.getZ(); zz <= max.getZ(); zz++) {
            for (int xx = min.getX(); xx <= max.getX(); xx++) {
                // get the y value of the topmost block
                int yy = getNextSolid(buffer, xx, yStart, zz, yEnd);
                if (yy < yEnd) {
                    continue;
                }
                // some random value to compare to odds
                float value = hashToFloat(xx, zz, seed);
                // get the flower for the current cell, may be null
                flowerCells.setSeed(intSeed);
                flowerDensities.setSeed(intSeed);
                Flower flower = FLOWERS[(int) flowerCells.getValue(xx, 0, zz)];
                // check if we have a flower based on odds for the cell
                if (flower == null || value < flowerOdds.getValue(xx, 0, zz)) {
                    // try with a different seed to create a second layer of flower cells, giving us some overlap
                    flowerCells.setSeed(intSeed2);
                    flowerDensities.setSeed(intSeed2);
                    flower = FLOWERS[(int) flowerCells.getValue(xx, 0, zz)];
                    // try the check again if we have a flower
                    if (flower != null && value < flowerOdds.getValue(xx, 0, zz)) {
                        // check failed, no flowers
                        flower = null;
                    }
                }
                if (flower != null) {
                    buffer.setBlock(xx, yy + 1, zz, flower.getBlock());
                    if (flower.isDoubleHeight()) {
                        buffer.setBlock(xx, yy + 2, zz, flower.getUpperBlock());
                    }
                } else if (value >= GRASS_ODDS) {
                    // if no flower, check if the value is greater than the grass odds
                    if (value >= DOUBLE_GRASS_ODDS && yy + 1 < yMax) {
                        // tall grass is a bit more rare
                        buffer.setBlockType(xx, yy + 1, zz, BlockTypes.MELON_BLOCK);
                        buffer.setBlockType(xx, yy + 2, zz, BlockTypes.MELON_BLOCK);
                    } else {
                        buffer.setBlock(xx, yy + 1, zz, TALL_GRASS);
                    }
                }
                // locations underneath this one will only get grass and less of it as they are covered
                yy = getNextAir(buffer, xx, yy, zz, yEnd);
                yy = getNextSolid(buffer, xx, yy, zz, yEnd);
                while (yy >= yEnd) {
                    // generate a new random value for the y coordinate
                    value = hashToFloat(xx, zz, seed ^ yy);
                    if (value >= COVERED_GRASS_ODDS) {
                        buffer.setBlock(xx, yy + 1, zz, TALL_GRASS);
                    }
                    yy = getNextAir(buffer, xx, yy, zz, yEnd);
                    yy = getNextSolid(buffer, xx, yy, zz, yEnd);
                }
            }
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private static int getNextSolid(MutableBlockBuffer buffer, int x, int y, int z, int yEnd) {
        for (; y >= yEnd && buffer.getBlockType(x, y, z).equals(BlockTypes.AIR); y--) {
            // iterate until we reach solid
        }
        return y;
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private static int getNextAir(MutableBlockBuffer buffer, int x, int y, int z, int yEnd) {
        for (; y >= yEnd && !buffer.getBlockType(x, y, z).equals(BlockTypes.AIR); y--) {
            // iterate until we exit the solid column
        }
        return y;
    }

    // TODO: move this to a util class?
    private static float hashToFloat(int x, int y, long seed) {
        final long hash = x * 73428767 ^ y * 9122569 ^ seed * 457;
        return (hash * (hash + 456149) & 0x00ffffff) / (float) 0x01000000;
    }

    private static class Flower {

        private final BlockState block;
        private final boolean doubleHeight;
        private final BlockState upperBlock;

        private Flower(BlockType block, boolean doubleHeight) {
            this.block = block.getDefaultState();
            this.doubleHeight = doubleHeight;
            upperBlock = this.block;
        }

        private BlockState getBlock() {
            return block;
        }

        private boolean isDoubleHeight() {
            return doubleHeight;
        }

        private BlockState getUpperBlock() {
            return upperBlock;
        }
    }

    private static class RarityCurve extends Module {

        private double degree;

        private RarityCurve() {
            super(1);
        }

        private void setDegree(double degree) {
            this.degree = degree;
        }

        @Override
        public int getSourceModuleCount() {
            return 1;
        }

        @Override
        public double getValue(double x, double y, double z) {
            final double value = sourceModule[0].getValue(x, y, z);
            return 1 - Math.pow(1 - value, degree);
        }
    }
}
