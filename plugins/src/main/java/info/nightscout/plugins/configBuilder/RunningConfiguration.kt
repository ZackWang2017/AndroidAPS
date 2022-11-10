package info.nightscout.plugins.configBuilder

import info.nightscout.core.main.R
import info.nightscout.androidaps.interfaces.ActivePlugin
import info.nightscout.interfaces.ConfigBuilder
import info.nightscout.androidaps.interfaces.Insulin
import info.nightscout.interfaces.NsClient
import info.nightscout.androidaps.interfaces.PumpSync
import info.nightscout.androidaps.interfaces.Sensitivity
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification
import info.nightscout.interfaces.pump.defs.PumpType
import info.nightscout.interfaces.Config
import info.nightscout.interfaces.PluginType
import info.nightscout.interfaces.notifications.Notification
import info.nightscout.plugins.sync.nsShared.events.EventNSClientNewLog
import info.nightscout.rx.bus.RxBus
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import info.nightscout.sdk.remotemodel.RemoteDeviceStatus
import info.nightscout.shared.interfaces.ResourceHelper
import info.nightscout.shared.sharedPreferences.SP
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RunningConfiguration @Inject constructor(
    private val activePlugin: ActivePlugin,
    private val configBuilder: ConfigBuilder,
    private val sp: SP,
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val rh: ResourceHelper,
    private val rxBus: RxBus,
    private val pumpSync: PumpSync
) {

    private var counter = 0
    private val every = 12 // Send only every 12 device status to save traffic

    // called in AAPS mode only
    fun configuration(): JSONObject {
        val json = JSONObject()
        val pumpInterface = activePlugin.activePump

        if (!pumpInterface.isInitialized()) return json
        if (counter++ % every == 0)
            try {
                val insulinInterface = activePlugin.activeInsulin
                val sensitivityInterface = activePlugin.activeSensitivity
                val overviewInterface = activePlugin.activeOverview
                val safetyInterface = activePlugin.activeSafety

                json.put("insulin", insulinInterface.id.value)
                json.put("insulinConfiguration", insulinInterface.configuration())
                json.put("sensitivity", sensitivityInterface.id.value)
                json.put("sensitivityConfiguration", sensitivityInterface.configuration())
                json.put("overviewConfiguration", overviewInterface.configuration())
                json.put("safetyConfiguration", safetyInterface.configuration())
                json.put("pump", pumpInterface.model().description)
                json.put("version", config.VERSION_NAME)
            } catch (e: JSONException) {
                aapsLogger.error("Unhandled exception", e)
            }
        return json
    }

    // called in NSClient mode only
    fun apply(configuration: RemoteDeviceStatus.Configuration, version: NsClient.Version) {
        assert(config.NSCLIENT)

        configuration.version?.let {
            rxBus.send(EventNSClientNewLog("VERSION", "Received AndroidAPS version  $it", version))
            if (config.VERSION_NAME.startsWith(it).not())
                rxBus.send(EventNewNotification(Notification(Notification.NSCLIENT_VERSION_DOES_NOT_MATCH, rh.gs(R.string.nsclient_version_does_not_match), Notification.NORMAL)))
        }
        configuration.insulin?.let {
            val insulin = Insulin.InsulinType.fromInt(it)
            for (p in activePlugin.getSpecificPluginsListByInterface(Insulin::class.java)) {
                val insulinPlugin = p as Insulin
                if (insulinPlugin.id == insulin) {
                    if (!p.isEnabled()) {
                        aapsLogger.debug(LTag.CORE, "Changing insulin plugin to ${insulin.name}")
                        configBuilder.performPluginSwitch(p, true, PluginType.INSULIN)
                    }
                    configuration.insulinConfiguration?.let { ic -> insulinPlugin.applyConfiguration(ic) }
                }
            }
        }

        configuration.sensitivity?.let {
            val sensitivity = Sensitivity.SensitivityType.fromInt(it)
            for (p in activePlugin.getSpecificPluginsListByInterface(Sensitivity::class.java)) {
                val sensitivityPlugin = p as Sensitivity
                if (sensitivityPlugin.id == sensitivity) {
                    if (!p.isEnabled()) {
                        aapsLogger.debug(LTag.CORE, "Changing sensitivity plugin to ${sensitivity.name}")
                        configBuilder.performPluginSwitch(p, true, PluginType.SENSITIVITY)
                    }
                    configuration.sensitivityConfiguration?.let { sc -> sensitivityPlugin.applyConfiguration(sc) }
                }
            }
        }

        configuration.pump?.let {
            if (sp.getString(R.string.key_virtualpump_type, "fake") != it) {
                sp.putString(R.string.key_virtualpump_type, it)
                activePlugin.activePump.pumpDescription.fillFor(PumpType.getByDescription(it))
                pumpSync.connectNewPump(endRunning = false) // do not end running TBRs, we call this only to accept data properly
                aapsLogger.debug(LTag.CORE, "Changing pump type to $it")
            }
        }

        configuration.overviewConfiguration?.let {
            activePlugin.activeOverview.applyConfiguration(it)
        }

        configuration.safetyConfiguration?.let {
            activePlugin.activeSafety.applyConfiguration(it)
        }
    }
}