package com.neurodoc

import com.facebook.react.bridge.ReactApplicationContext

class NeurodocModule(reactContext: ReactApplicationContext) :
  NativeNeurodocSpec(reactContext) {

  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }

  companion object {
    const val NAME = NativeNeurodocSpec.NAME
  }
}
