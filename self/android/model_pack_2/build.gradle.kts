plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("model_pack_2")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
