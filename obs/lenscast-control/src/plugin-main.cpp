#include <obs-module.h>
#include <obs-frontend-api.h>

#include "lenscast-dock.hpp"

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("lenscast-control", "en-US")

MODULE_EXPORT const char *obs_module_description(void)
{
	return obs_module_text("Description");
}

#define DOCK_ID "lenscast_control_dock"

static LenscastDock *dock = nullptr;
static obs_hotkey_id hk_startstop = OBS_INVALID_HOTKEY_ID;
static obs_hotkey_id hk_lens = OBS_INVALID_HOTKEY_ID;
static obs_hotkey_id hk_torch = OBS_INVALID_HOTKEY_ID;
static obs_hotkey_id hk_snap = OBS_INVALID_HOTKEY_ID;

static void cb_startstop(void *, obs_hotkey_id, obs_hotkey_t *, bool pressed)
{
	if (pressed && dock)
		dock->hkStartStop();
}
static void cb_lens(void *, obs_hotkey_id, obs_hotkey_t *, bool pressed)
{
	if (pressed && dock)
		dock->hkToggleLens();
}
static void cb_torch(void *, obs_hotkey_id, obs_hotkey_t *, bool pressed)
{
	if (pressed && dock)
		dock->hkToggleTorch();
}
static void cb_snap(void *, obs_hotkey_id, obs_hotkey_t *, bool pressed)
{
	if (pressed && dock)
		dock->hkSnapshot();
}

bool obs_module_load(void)
{
	// Modules load on the UI thread after the Qt app + main window exist, so it's safe to
	// build the widget and register the dock here.
	dock = new LenscastDock();
	obs_frontend_add_dock_by_id(DOCK_ID, "Lenscast Control", dock);

	hk_startstop = obs_hotkey_register_frontend("lenscast.startstop",
						    "Lenscast: Start/stop streaming", cb_startstop, nullptr);
	hk_lens = obs_hotkey_register_frontend("lenscast.lens", "Lenscast: Switch camera", cb_lens, nullptr);
	hk_torch = obs_hotkey_register_frontend("lenscast.torch", "Lenscast: Toggle torch", cb_torch, nullptr);
	hk_snap = obs_hotkey_register_frontend("lenscast.snapshot", "Lenscast: Snapshot", cb_snap, nullptr);

	blog(LOG_INFO, "[lenscast-control] loaded (v%s)", PLUGIN_VERSION);
	return true;
}

void obs_module_unload(void)
{
	obs_hotkey_unregister(hk_startstop);
	obs_hotkey_unregister(hk_lens);
	obs_hotkey_unregister(hk_torch);
	obs_hotkey_unregister(hk_snap);
	// The dock widget is owned by the OBS dock wrapper; remove_dock disposes of it.
	obs_frontend_remove_dock(DOCK_ID);
	dock = nullptr;
}
