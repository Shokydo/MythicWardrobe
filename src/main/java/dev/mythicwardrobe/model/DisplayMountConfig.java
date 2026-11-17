package dev.mythicwardrobe.model;

import org.bukkit.entity.ItemDisplay;

public record DisplayMountConfig(
        MountPoint mountPoint,
        float offsetX,
        float offsetY,
        float offsetZ,
        float scaleX,
        float scaleY,
        float scaleZ,
        float rotX,
        float rotY,
        float rotZ,
        boolean pitchFollow,
        boolean hideFromWearer,
        ItemDisplay.ItemDisplayTransform transform
) {
    public enum MountPoint {
        HEAD,
        BACK,
        WAIST
    }

    public static DisplayMountConfig defaultHead() {
        return new DisplayMountConfig(
                MountPoint.HEAD, 0f, 0f, 0f, 1f, 1f, 1f, 0f, 0f, 0f,
                true, false, ItemDisplay.ItemDisplayTransform.HEAD
        );
    }

    public static DisplayMountConfig defaultBack() {
        return new DisplayMountConfig(
                MountPoint.BACK, 0f, 1.15f, -0.26f, 1f, 1f, 1f, 0f, 0f, 0f,
                false, false, ItemDisplay.ItemDisplayTransform.HEAD
        );
    }

    public DisplayMountConfig mergeOverride(DisplayMountConfig override) {
        if (override == null) return this;
        return new DisplayMountConfig(
                override.mountPoint != null ? override.mountPoint : this.mountPoint,
                override.offsetX,
                override.offsetY,
                override.offsetZ,
                override.scaleX,
                override.scaleY,
                override.scaleZ,
                override.rotX,
                override.rotY,
                override.rotZ,
                override.pitchFollow,
                override.hideFromWearer,
                override.transform != null ? override.transform : this.transform
        );
    }
}
