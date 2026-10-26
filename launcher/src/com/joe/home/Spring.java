package com.joe.home;

import android.view.animation.Interpolator;

/** Under-damped spring: fast start, about 12% overshoot, settled by t = 1. */
final class Spring implements Interpolator {
    private final float damping;
    private final float frequency;

    Spring(float damping, float frequency) {
        this.damping = damping;
        this.frequency = frequency;
    }

    @Override
    public float getInterpolation(float t) {
        if (t >= 1f) return 1f;
        return (float) (1 - Math.exp(-damping * t) * Math.cos(frequency * t));
    }
}
