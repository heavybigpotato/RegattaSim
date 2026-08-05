package com.bluemeridian.render;

import com.bluemeridian.core.ocean.CascadeSettings;

/**
 * Quality tiers, matching the performance budget in the design brief.
 *
 * <p>The tier fixes the FFT resolution, the number of cascades and the density of
 * the projected grid. Everything else - the shading itself - is identical across
 * tiers, so a screenshot from a cheap phone is the same picture with less detail
 * rather than a different-looking ocean.
 *
 * <p>Detection picks a starting point; the player can always override it. A phone
 * struggling at ULTRA is the player's business, not the engine's.
 */
public enum RenderQuality {

    /** Snapdragon 8 Gen 2 and up: three cascades at 256, full grid. */
    ULTRA(256, 3, 320, 200),
    /** Snapdragon 7 Gen and up: three cascades at 128. */
    HIGH(128, 3, 256, 160),
    /** Mid-range: two cascades at 128, coarser grid. */
    MEDIUM(128, 2, 192, 120),
    /** 3 GB devices: two cascades at 64. */
    LOW(64, 2, 128, 80);

    private final int fftResolution;
    private final int cascadeCount;
    private final int gridColumns;
    private final int gridRows;

    RenderQuality(int fftResolution, int cascadeCount, int gridColumns, int gridRows) {
        this.fftResolution = fftResolution;
        this.cascadeCount = cascadeCount;
        this.gridColumns = gridColumns;
        this.gridRows = gridRows;
    }

    public int fftResolution() {
        return fftResolution;
    }

    public int cascadeCount() {
        return cascadeCount;
    }

    public int gridColumns() {
        return gridColumns;
    }

    public int gridRows() {
        return gridRows;
    }

    /** Cascade layout for this tier. */
    public CascadeSettings cascades() {
        return cascadeCount >= 3
                ? CascadeSettings.standard(fftResolution)
                : CascadeSettings.reduced(fftResolution);
    }

    /** Draw calls the FFT costs per frame, useful when budgeting a frame. */
    public int fftDrawCallsPerFrame() {
        int stages = Integer.numberOfTrailingZeros(fftResolution);
        // Per cascade: 2 evolve + 2 transforms of (2 axes * stages) + 2 assemble.
        return cascadeCount * (2 + 2 * 2 * stages + 2);
    }
}
