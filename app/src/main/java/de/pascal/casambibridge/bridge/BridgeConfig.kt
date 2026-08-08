package de.pascal.casambibridge.bridge

data class BridgeConfig(
    val casambiMac:String="7A:1B:1E:FF:63:4A", val casambiNetworkName:String="", val casambiPassword:String="", val casambiProtocolVersion:Int=11, val casambiKeyId:Int=2, val casambiKeyHex:String="",
    val mqttHost:String="", val mqttPort:Int=1883, val mqttUser:String="", val mqttPassword:String="", val baseTopic:String="casambi_bridge", val discoveryPrefix:String="homeassistant",
    val smbDebugEnabled:Boolean=false, val smbServer:String="", val smbShare:String="", val smbPath:String="casambi_debug", val smbDomain:String="", val smbUser:String="", val smbPassword:String="",
    val tcpLogEnabled:Boolean=false, val tcpLogPort:Int=5555,
    val webInterfaceEnabled:Boolean=false, val webInterfacePort:Int=8080,
    val autoApiFetchEnabled:Boolean=false,
    val webSocketLiveEnabled:Boolean=false
)
