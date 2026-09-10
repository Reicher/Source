plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("source_ai_model_3")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
