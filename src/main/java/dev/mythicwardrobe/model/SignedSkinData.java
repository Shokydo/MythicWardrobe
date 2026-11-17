package dev.mythicwardrobe.model;

public record SignedSkinData(
        String value,
        String signature,
        String textureUrl,
        SkinModelVariant variant
) {
    public boolean isValid() {
        return value != null && !value.isBlank() && signature != null && !signature.isBlank();
    }
}
