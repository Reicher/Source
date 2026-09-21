plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("model_pack_1")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
