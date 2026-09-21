plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("model_pack_3")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
