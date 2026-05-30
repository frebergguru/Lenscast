#include "lenscast-dock.hpp"
#include "lenscast-client.hpp"

#include <obs-module.h>
#include <obs-frontend-api.h>
#include <util/platform.h>

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QFormLayout>
#include <QGridLayout>
#include <QLineEdit>
#include <QLabel>
#include <QPushButton>
#include <QComboBox>
#include <QTimer>
#include <QJsonArray>
#include <QFileInfo>

// ---- config persistence (a small json under the module's config dir) --------------------

static QString configFilePath()
{
	char *p = obs_module_config_path("config.json");
	QString s = QString::fromUtf8(p ? p : "");
	bfree(p);
	return s;
}

void LenscastDock::loadConfig()
{
	char *p = obs_module_config_path("config.json");
	if (!p)
		return;
	obs_data_t *d = obs_data_create_from_json_file(p);
	bfree(p);
	if (!d)
		return;
	hostEdit_->setText(QString::fromUtf8(obs_data_get_string(d, "host")));
	const int port = (int)obs_data_get_int(d, "port");
	portEdit_->setText(QString::number(port ? port : 8088));
	tokenEdit_->setText(QString::fromUtf8(obs_data_get_string(d, "token")));
	obs_data_release(d);
}

void LenscastDock::saveConfig()
{
	const QString file = configFilePath();
	if (file.isEmpty())
		return;
	// Make sure the parent dir exists before writing.
	const QByteArray dir = QFileInfo(file).absolutePath().toUtf8();
	os_mkdirs(dir.constData());

	obs_data_t *d = obs_data_create();
	obs_data_set_string(d, "host", hostEdit_->text().trimmed().toUtf8().constData());
	obs_data_set_int(d, "port", portEdit_->text().toInt());
	obs_data_set_string(d, "token", tokenEdit_->text().trimmed().toUtf8().constData());
	obs_data_save_json(d, file.toUtf8().constData());
	obs_data_release(d);
}

// ---- construction -----------------------------------------------------------------------

LenscastDock::LenscastDock(QWidget *parent) : QFrame(parent), client_(new LenscastClient(this))
{
	buildUi();
	loadConfig();
	applyConfigToClient();

	connect(client_, &LenscastClient::statusReady, this, &LenscastDock::onStatus);
	connect(client_, &LenscastClient::requestFailed, this, &LenscastDock::onFailed);
	connect(client_, &LenscastClient::actionDone, this, &LenscastDock::onActionDone);

	timer_ = new QTimer(this);
	timer_->setInterval(2000);
	connect(timer_, &QTimer::timeout, this, &LenscastDock::poll);
	timer_->start();
	poll();
}

LenscastDock::~LenscastDock() = default;

void LenscastDock::buildUi()
{
	auto *root = new QVBoxLayout(this);
	root->setContentsMargins(8, 8, 8, 8);
	root->setSpacing(8);

	// Connection
	auto *form = new QFormLayout();
	hostEdit_ = new QLineEdit(this);
	hostEdit_->setPlaceholderText(QStringLiteral("192.168.1.50"));
	portEdit_ = new QLineEdit(this);
	portEdit_->setText(QStringLiteral("8088"));
	portEdit_->setMaximumWidth(80);
	tokenEdit_ = new QLineEdit(this);
	tokenEdit_->setEchoMode(QLineEdit::Password);
	tokenEdit_->setPlaceholderText(QStringLiteral("API token (Settings → REST API → Copy)"));
	form->addRow(tr("Phone IP"), hostEdit_);
	form->addRow(tr("API port"), portEdit_);
	form->addRow(tr("Token"), tokenEdit_);
	root->addLayout(form);

	auto *connectBtn = new QPushButton(tr("Connect / Save"), this);
	connect(connectBtn, &QPushButton::clicked, this, &LenscastDock::onConnectClicked);
	root->addWidget(connectBtn);

	statusLabel_ = new QLabel(tr("Not connected"), this);
	statusLabel_->setWordWrap(true);
	root->addWidget(statusLabel_);

	// Stream controls
	auto *streamRow = new QHBoxLayout();
	startBtn_ = new QPushButton(tr("Start"), this);
	stopBtn_ = new QPushButton(tr("Stop"), this);
	connect(startBtn_, &QPushButton::clicked, this, [this]() { client_->post(QStringLiteral("/stream/start")); });
	connect(stopBtn_, &QPushButton::clicked, this, [this]() { client_->post(QStringLiteral("/stream/stop")); });
	streamRow->addWidget(startBtn_);
	streamRow->addWidget(stopBtn_);
	root->addLayout(streamRow);

	// Camera controls
	auto *grid = new QGridLayout();
	lensBtn_ = new QPushButton(tr("Switch camera"), this);
	torchBtn_ = new QPushButton(tr("Torch"), this);
	snapBtn_ = new QPushButton(tr("Snapshot"), this);
	zoomInBtn_ = new QPushButton(tr("Zoom +"), this);
	zoomOutBtn_ = new QPushButton(tr("Zoom −"), this);
	connect(lensBtn_, &QPushButton::clicked, this, [this]() { client_->post(QStringLiteral("/camera/lens")); });
	connect(torchBtn_, &QPushButton::clicked, this, [this]() { client_->post(QStringLiteral("/camera/torch")); });
	connect(snapBtn_, &QPushButton::clicked, this, [this]() { client_->post(QStringLiteral("/camera/snapshot")); });
	connect(zoomInBtn_, &QPushButton::clicked, this,
		[this]() { client_->post(QStringLiteral("/camera/zoom"), {{"factor", 1.25}}); });
	connect(zoomOutBtn_, &QPushButton::clicked, this,
		[this]() { client_->post(QStringLiteral("/camera/zoom"), {{"factor", 0.8}}); });
	grid->addWidget(lensBtn_, 0, 0);
	grid->addWidget(torchBtn_, 0, 1);
	grid->addWidget(snapBtn_, 0, 2);
	grid->addWidget(zoomInBtn_, 1, 0);
	grid->addWidget(zoomOutBtn_, 1, 1);
	root->addLayout(grid);

	// Resolution
	auto *resRow = new QHBoxLayout();
	resRow->addWidget(new QLabel(tr("Resolution"), this));
	resolutionBox_ = new QComboBox(this);
	connect(resolutionBox_, &QComboBox::activated, this, [this](int) {
		const QString v = resolutionBox_->currentText();
		if (!v.isEmpty())
			client_->post(QStringLiteral("/camera/resolution"), {{"value", v}});
	});
	resRow->addWidget(resolutionBox_, 1);
	root->addLayout(resRow);

	// Media source
	sourceBtn_ = new QPushButton(tr("Add / refresh OBS Media Source"), this);
	connect(sourceBtn_, &QPushButton::clicked, this, &LenscastDock::addOrRefreshMediaSource);
	root->addWidget(sourceBtn_);

	noteLabel_ = new QLabel(QString(), this);
	noteLabel_->setWordWrap(true);
	noteLabel_->setStyleSheet(QStringLiteral("color: palette(mid);"));
	root->addWidget(noteLabel_);

	root->addStretch(1);
	setMinimumWidth(260);
}

// ---- config / status --------------------------------------------------------------------

void LenscastDock::applyConfigToClient()
{
	client_->configure(hostEdit_->text(), portEdit_->text().toInt(), tokenEdit_->text(), false);
}

void LenscastDock::onConnectClicked()
{
	applyConfigToClient();
	saveConfig();
	note(tr("Saved. Polling status…"));
	poll();
}

void LenscastDock::poll()
{
	if (client_->configured())
		client_->fetchStatus();
}

void LenscastDock::onStatus(const QJsonObject &s)
{
	lastStatus_ = s;
	const QString state = s.value(QStringLiteral("state")).toString();
	const QString proto = s.value(QStringLiteral("protocol")).toString();
	streaming_ = (state == QStringLiteral("streaming"));

	QString line = tr("State: %1").arg(state.isEmpty() ? tr("unknown") : state);
	if (!proto.isEmpty())
		line += QStringLiteral(" · %1").arg(proto);
	const QString res = s.value(QStringLiteral("resolution")).toString();
	if (!res.isEmpty())
		line += QStringLiteral(" · %1").arg(res);
	statusLabel_->setText(line);

	startBtn_->setEnabled(!streaming_);
	stopBtn_->setEnabled(streaming_);

	const bool torch = s.value(QStringLiteral("torch")).toBool();
	torchBtn_->setText(torch ? tr("Torch (on)") : tr("Torch"));

	// Refresh the resolution list without clobbering an in-progress selection.
	const QJsonArray avail = s.value(QStringLiteral("availableResolutions")).toArray();
	if (!avail.isEmpty()) {
		QStringList items;
		for (const auto &v : avail)
			items << v.toString();
		if (items != [this] {
			    QStringList cur;
			    for (int i = 0; i < resolutionBox_->count(); ++i)
				    cur << resolutionBox_->itemText(i);
			    return cur;
		    }()) {
			resolutionBox_->blockSignals(true);
			resolutionBox_->clear();
			resolutionBox_->addItems(items);
			resolutionBox_->blockSignals(false);
		}
		const int idx = resolutionBox_->findText(res);
		if (idx >= 0) {
			resolutionBox_->blockSignals(true);
			resolutionBox_->setCurrentIndex(idx);
			resolutionBox_->blockSignals(false);
		}
	}
}

void LenscastDock::onFailed(const QString &what)
{
	statusLabel_->setText(tr("Not reachable: %1").arg(what));
	streaming_ = false;
	startBtn_->setEnabled(true);
	stopBtn_->setEnabled(true);
}

void LenscastDock::onActionDone(const QString &path, bool ok, const QString &msg)
{
	if (!ok)
		note(tr("%1 failed: %2").arg(path, msg));
	poll(); // reflect the new state quickly
}

void LenscastDock::note(const QString &line)
{
	noteLabel_->setText(line);
}

// ---- media source -----------------------------------------------------------------------

QString LenscastDock::streamUrlFor(const QJsonObject &s) const
{
	const QString ip = hostEdit_->text().trimmed();
	if (ip.isEmpty())
		return {};
	const QString proto = s.value(QStringLiteral("protocol")).toString();
	if (proto == QStringLiteral("mjpeg"))
		return QStringLiteral("http://%1:%2/video").arg(ip).arg(s.value("mjpegPort").toInt(4747));
	if (proto == QStringLiteral("rtsp"))
		return QStringLiteral("rtsp://%1:%2/video").arg(ip).arg(s.value("rtspPort").toInt(5540));
	if (proto == QStringLiteral("srt"))
		return QStringLiteral("srt://%1:%2?mode=caller").arg(ip).arg(s.value("srtPort").toInt(9710));
	return {}; // rist / webrtc can't be played by a plain Media Source
}

void LenscastDock::addOrRefreshMediaSource()
{
	const QString url = streamUrlFor(lastStatus_);
	if (url.isEmpty()) {
		note(tr("Auto Media Source supports MJPEG, RTSP and SRT only — "
			"connect and pick one of those protocols on the phone first."));
		return;
	}

	static const char *kName = "Lenscast";
	obs_data_t *settings = obs_data_create();
	obs_data_set_bool(settings, "is_local_file", false);
	obs_data_set_string(settings, "input", url.toUtf8().constData());
	obs_data_set_string(settings, "input_format", "");
	obs_data_set_int(settings, "reconnect_delay_sec", 2);
	obs_data_set_bool(settings, "restart_on_activate", false); // keep a live feed running
	obs_data_set_bool(settings, "hw_decode", true);

	obs_source_t *existing = obs_get_source_by_name(kName);
	if (existing) {
		obs_source_update(existing, settings);
		obs_source_release(existing);
		note(tr("Media Source “%1” refreshed → %2").arg(kName, url));
	} else {
		obs_source_t *src = obs_source_create("ffmpeg_source", kName, settings, nullptr);
		obs_source_t *scene_src = obs_frontend_get_current_scene();
		obs_scene_t *scene = obs_scene_from_source(scene_src);
		if (scene && src)
			obs_scene_add(scene, src);
		obs_source_release(scene_src);
		obs_source_release(src);
		note(tr("Media Source “%1” added to the current scene → %2").arg(kName, url));
	}
	obs_data_release(settings);
}

// ---- hotkey entry points ----------------------------------------------------------------

void LenscastDock::hkStartStop()
{
	client_->post(streaming_ ? QStringLiteral("/stream/stop") : QStringLiteral("/stream/start"));
}
void LenscastDock::hkToggleLens()
{
	client_->post(QStringLiteral("/camera/lens"));
}
void LenscastDock::hkToggleTorch()
{
	client_->post(QStringLiteral("/camera/torch"));
}
void LenscastDock::hkSnapshot()
{
	client_->post(QStringLiteral("/camera/snapshot"));
}
