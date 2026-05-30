#include "lenscast-dock.hpp"
#include "lenscast-client.hpp"

#include <obs-module.h>
#include <obs-frontend-api.h>
#include <util/platform.h>

#include <QApplication>
#include <QStyle>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QGridLayout>
#include <QFormLayout>
#include <QScrollArea>
#include <QFrame>
#include <QLineEdit>
#include <QLabel>
#include <QPushButton>
#include <QToolButton>
#include <QComboBox>
#include <QTimer>
#include <QIcon>
#include <QJsonArray>
#include <QFileInfo>

// ---- small UI helpers -------------------------------------------------------------------

static QIcon themed(const char *name, QStyle::StandardPixmap fallback)
{
	QIcon i = QIcon::fromTheme(QString::fromUtf8(name));
	if (i.isNull() && qApp)
		i = qApp->style()->standardIcon(fallback);
	return i;
}

static QToolButton *quickButton(const QString &text, const QIcon &icon)
{
	auto *b = new QToolButton();
	b->setToolButtonStyle(Qt::ToolButtonTextUnderIcon);
	b->setIcon(icon);
	b->setIconSize(QSize(22, 22));
	b->setText(text);
	b->setAutoRaise(true);
	b->setMinimumWidth(70);
	b->setMinimumHeight(54);
	b->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
	return b;
}

static QLabel *sectionHeader(const QString &title)
{
	auto *l = new QLabel(title);
	QFont f = l->font();
	f.setBold(true);
	f.setPointSizeF(f.pointSizeF() * 0.95);
	l->setFont(f);
	l->setStyleSheet(QStringLiteral("color: palette(highlight); margin-top: 4px;"));
	return l;
}

static QFrame *hLine()
{
	auto *line = new QFrame();
	line->setFrameShape(QFrame::HLine);
	line->setFrameShadow(QFrame::Sunken);
	return line;
}

// ---- config persistence -----------------------------------------------------------------

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
	char *p = obs_module_config_path("config.json");
	if (!p)
		return;
	const QString file = QString::fromUtf8(p);
	bfree(p);
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
	// A scroll area keeps every section at its natural size — the dock can never compress them
	// into each other (which caused overlapping buttons + clipped labels). The firm minimum
	// size set on the dock at the end of this function makes OBS open it big enough that, in
	// practice, nothing needs scrolling. OBS persists whatever size you drag it to.
	auto *outer = new QVBoxLayout(this);
	outer->setContentsMargins(0, 0, 0, 0);
	auto *scroll = new QScrollArea(this);
	scroll->setWidgetResizable(true);
	scroll->setFrameShape(QFrame::NoFrame);
	scroll->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
	outer->addWidget(scroll);
	auto *content = new QWidget();
	scroll->setWidget(content);
	auto *root = new QVBoxLayout(content);
	root->setContentsMargins(12, 12, 12, 12);
	root->setSpacing(10);

	// ---- Connection ----
	root->addWidget(sectionHeader(tr("CONNECTION")));
	auto *form = new QFormLayout();
	form->setLabelAlignment(Qt::AlignRight);
	form->setHorizontalSpacing(10);
	form->setVerticalSpacing(8);
	hostEdit_ = new QLineEdit();
	hostEdit_->setPlaceholderText(QStringLiteral("192.168.1.50"));
	portEdit_ = new QLineEdit();
	portEdit_->setText(QStringLiteral("8088"));
	portEdit_->setMaximumWidth(72);
	tokenEdit_ = new QLineEdit();
	tokenEdit_->setEchoMode(QLineEdit::Password);
	tokenEdit_->setPlaceholderText(tr("Settings → REST API → Copy token"));
	auto *ipRow = new QHBoxLayout();
	ipRow->addWidget(hostEdit_, 1);
	ipRow->addWidget(new QLabel(tr("Port")));
	ipRow->addWidget(portEdit_);
	form->addRow(tr("Phone IP"), ipRow);
	form->addRow(tr("Token"), tokenEdit_);
	root->addLayout(form);

	auto *connectBtn = new QPushButton(themed("network-connect", QStyle::SP_DialogApplyButton),
					   tr("Connect / Save"));
	connect(connectBtn, &QPushButton::clicked, this, &LenscastDock::onConnectClicked);
	root->addWidget(connectBtn);

	auto *statusRow = new QHBoxLayout();
	statusDot_ = new QLabel(QStringLiteral("●"));
	statusDot_->setStyleSheet(QStringLiteral("color: gray; font-size: 15px;"));
	statusLabel_ = new QLabel(tr("Not connected"));
	statusLabel_->setWordWrap(true);
	statusRow->addWidget(statusDot_, 0);
	statusRow->addWidget(statusLabel_, 1);
	root->addLayout(statusRow);

	root->addWidget(hLine());

	// ---- Stream ----
	root->addWidget(sectionHeader(tr("STREAM")));
	auto *streamRow = new QHBoxLayout();
	startBtn_ = new QPushButton(qApp->style()->standardIcon(QStyle::SP_MediaPlay), tr("Start"));
	stopBtn_ = new QPushButton(qApp->style()->standardIcon(QStyle::SP_MediaStop), tr("Stop"));
	connect(startBtn_, &QPushButton::clicked, this, [this]() { client_->post(QStringLiteral("/stream/start")); });
	connect(stopBtn_, &QPushButton::clicked, this, [this]() { client_->post(QStringLiteral("/stream/stop")); });
	streamRow->addWidget(startBtn_);
	streamRow->addWidget(stopBtn_);
	root->addLayout(streamRow);

	auto *protoRow = new QHBoxLayout();
	protoRow->addWidget(new QLabel(tr("Protocol")));
	protocolBox_ = new QComboBox();
	protocolBox_->addItems({QStringLiteral("mjpeg"), QStringLiteral("rtsp"), QStringLiteral("srt"),
				QStringLiteral("rist"), QStringLiteral("webrtc")});
	connect(protocolBox_, &QComboBox::activated, this, [this](int) {
		client_->post(QStringLiteral("/camera/protocol"),
			      {{"value", protocolBox_->currentText()}});
	});
	protoRow->addWidget(protocolBox_, 1);
	root->addLayout(protoRow);

	root->addWidget(hLine());

	// ---- Camera quick-controls ----
	root->addWidget(sectionHeader(tr("CAMERA")));
	auto *grid = new QGridLayout();
	grid->setHorizontalSpacing(6);
	grid->setVerticalSpacing(6);

	lensBtn_ = quickButton(tr("Switch"), themed("camera-switch", QStyle::SP_BrowserReload));
	torchBtn_ = quickButton(tr("Torch"), themed("flash-on", QStyle::SP_DialogYesButton));
	mirrorBtn_ = quickButton(tr("Mirror"), themed("object-flip-horizontal", QStyle::SP_BrowserReload));
	afBtn_ = quickButton(tr("Focus"), themed("zoom-fit-best", QStyle::SP_FileDialogContentsView));
	zoomInBtn_ = quickButton(tr("Zoom +"), themed("zoom-in", QStyle::SP_ArrowUp));
	zoomOutBtn_ = quickButton(tr("Zoom −"), themed("zoom-out", QStyle::SP_ArrowDown));
	evUpBtn_ = quickButton(tr("EV +"), themed("list-add", QStyle::SP_ArrowUp));
	evDownBtn_ = quickButton(tr("EV −"), themed("list-remove", QStyle::SP_ArrowDown));
	snapBtn_ = quickButton(tr("Snapshot"), themed("camera-photo", QStyle::SP_DialogSaveButton));

	torchBtn_->setCheckable(true);
	mirrorBtn_->setCheckable(true);
	afBtn_->setCheckable(true);

	connect(lensBtn_, &QToolButton::clicked, this, [this]() { client_->post(QStringLiteral("/camera/lens")); });
	connect(torchBtn_, &QToolButton::clicked, this, [this]() { client_->post(QStringLiteral("/camera/torch")); });
	connect(mirrorBtn_, &QToolButton::clicked, this, [this]() { client_->post(QStringLiteral("/camera/mirror")); });
	connect(afBtn_, &QToolButton::clicked, this, [this]() { client_->post(QStringLiteral("/camera/af")); });
	connect(zoomInBtn_, &QToolButton::clicked, this,
		[this]() { client_->post(QStringLiteral("/camera/zoom"), {{"factor", 1.25}}); });
	connect(zoomOutBtn_, &QToolButton::clicked, this,
		[this]() { client_->post(QStringLiteral("/camera/zoom"), {{"factor", 0.8}}); });
	connect(evUpBtn_, &QToolButton::clicked, this,
		[this]() { client_->post(QStringLiteral("/camera/exposure"), {{"delta", 1}}); });
	connect(evDownBtn_, &QToolButton::clicked, this,
		[this]() { client_->post(QStringLiteral("/camera/exposure"), {{"delta", -1}}); });
	connect(snapBtn_, &QToolButton::clicked, this, [this]() { client_->post(QStringLiteral("/camera/snapshot")); });

	grid->addWidget(lensBtn_, 0, 0);
	grid->addWidget(torchBtn_, 0, 1);
	grid->addWidget(mirrorBtn_, 0, 2);
	grid->addWidget(afBtn_, 0, 3);
	grid->addWidget(zoomInBtn_, 1, 0);
	grid->addWidget(zoomOutBtn_, 1, 1);
	grid->addWidget(evUpBtn_, 1, 2);
	grid->addWidget(evDownBtn_, 1, 3);
	// Snapshot spans the full width on its own row so it can't collide with the grid above.
	snapBtn_->setToolButtonStyle(Qt::ToolButtonTextBesideIcon);
	snapBtn_->setMinimumHeight(36);
	grid->addWidget(snapBtn_, 2, 0, 1, 4);
	for (int c = 0; c < 4; ++c)
		grid->setColumnStretch(c, 1); // equal-width columns
	root->addLayout(grid);

	auto *rfRow = new QHBoxLayout();
	rfRow->addWidget(new QLabel(tr("Res")));
	resolutionBox_ = new QComboBox();
	connect(resolutionBox_, &QComboBox::activated, this, [this](int) {
		if (!resolutionBox_->currentText().isEmpty())
			client_->post(QStringLiteral("/camera/resolution"),
				      {{"value", resolutionBox_->currentText()}});
	});
	rfRow->addWidget(resolutionBox_, 1);
	rfRow->addWidget(new QLabel(tr("FPS")));
	fpsBox_ = new QComboBox();
	connect(fpsBox_, &QComboBox::activated, this, [this](int) {
		client_->post(QStringLiteral("/camera/fps"), {{"value", fpsBox_->currentText().toInt()}});
	});
	rfRow->addWidget(fpsBox_);
	root->addLayout(rfRow);

	root->addWidget(hLine());

	// ---- OBS source ----
	root->addWidget(sectionHeader(tr("OBS SOURCE")));
	sourceBtn_ = new QPushButton(themed("list-add", QStyle::SP_FileDialogNewFolder),
				     tr("Add / refresh source in current scene"));
	connect(sourceBtn_, &QPushButton::clicked, this, &LenscastDock::addOrRefreshSource);
	root->addWidget(sourceBtn_);

	noteLabel_ = new QLabel(QString());
	noteLabel_->setWordWrap(true);
	noteLabel_->setStyleSheet(QStringLiteral("color: palette(mid);"));
	root->addWidget(noteLabel_);

	root->addStretch(1);
	setMinimumSize(380, 760); // floor on BOTH dimensions so OBS can't collapse it
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

static void setComboItems(QComboBox *box, const QStringList &items, const QString &current)
{
	QStringList cur;
	for (int i = 0; i < box->count(); ++i)
		cur << box->itemText(i);
	if (cur != items) {
		box->blockSignals(true);
		box->clear();
		box->addItems(items);
		box->blockSignals(false);
	}
	const int idx = box->findText(current);
	if (idx >= 0 && idx != box->currentIndex()) {
		box->blockSignals(true);
		box->setCurrentIndex(idx);
		box->blockSignals(false);
	}
}

void LenscastDock::onStatus(const QJsonObject &s)
{
	lastStatus_ = s;
	const QString state = s.value(QStringLiteral("state")).toString();
	const QString proto = s.value(QStringLiteral("protocol")).toString();
	streaming_ = (state == QStringLiteral("streaming"));

	QString line = state.isEmpty() ? tr("connected") : state;
	if (!proto.isEmpty())
		line += QStringLiteral(" · %1").arg(proto);
	const QString res = s.value(QStringLiteral("resolution")).toString();
	if (!res.isEmpty())
		line += QStringLiteral(" · %1").arg(res);
	if (s.contains(QStringLiteral("fps")))
		line += QStringLiteral(" · %1 fps").arg(s.value(QStringLiteral("fps")).toInt());
	statusLabel_->setText(line);

	const char *color = streaming_ ? "#3fbf3f" : (state == QStringLiteral("error") ? "#d33" : "#999");
	statusDot_->setStyleSheet(QStringLiteral("color: %1; font-size: 15px;").arg(QString::fromUtf8(color)));

	startBtn_->setEnabled(!streaming_);
	stopBtn_->setEnabled(streaming_);
	// Protocol is locked while streaming (the API rejects it mid-stream).
	protocolBox_->setEnabled(!streaming_);

	torchBtn_->setChecked(s.value(QStringLiteral("torch")).toBool());
	mirrorBtn_->setChecked(s.value(QStringLiteral("mirror")).toBool());
	afBtn_->setChecked(s.value(QStringLiteral("continuousAf")).toBool());

	// Reflect the phone's current protocol without rebuilding the fixed list.
	if (!proto.isEmpty()) {
		const int pidx = protocolBox_->findText(proto);
		if (pidx >= 0 && pidx != protocolBox_->currentIndex()) {
			protocolBox_->blockSignals(true);
			protocolBox_->setCurrentIndex(pidx);
			protocolBox_->blockSignals(false);
		}
	}

	const QJsonArray ares = s.value(QStringLiteral("availableResolutions")).toArray();
	if (!ares.isEmpty()) {
		QStringList items;
		for (const auto &v : ares)
			items << v.toString();
		setComboItems(resolutionBox_, items, res);
	}
	const QJsonArray afps = s.value(QStringLiteral("availableFps")).toArray();
	if (!afps.isEmpty()) {
		QStringList items;
		for (const auto &v : afps)
			items << QString::number(v.toInt());
		setComboItems(fpsBox_, items, QString::number(s.value(QStringLiteral("fps")).toInt()));
	}
}

void LenscastDock::onFailed(const QString &what)
{
	statusLabel_->setText(tr("Not reachable: %1").arg(what));
	statusDot_->setStyleSheet(QStringLiteral("color: #d33; font-size: 15px;"));
	streaming_ = false;
	startBtn_->setEnabled(true);
	stopBtn_->setEnabled(true);
}

void LenscastDock::onActionDone(const QString &path, bool ok, const QString &msg)
{
	if (!ok) {
		if (path.contains(QStringLiteral("protocol")) && msg.contains(QStringLiteral("503")))
			note(tr("Stop the stream before changing protocol."), true);
		else
			note(tr("%1 failed: %2").arg(path, msg), true);
	}
	poll();
}

void LenscastDock::note(const QString &line, bool error)
{
	noteLabel_->setText(line);
	noteLabel_->setStyleSheet(error ? QStringLiteral("color: #d33;")
					: QStringLiteral("color: palette(mid);"));
}

// ---- source creation --------------------------------------------------------------------

QString LenscastDock::streamUrlFor(const QJsonObject &s, bool &isBrowser) const
{
	isBrowser = false;
	const QString ip = hostEdit_->text().trimmed();
	if (ip.isEmpty())
		return {};
	const QString proto = s.value(QStringLiteral("protocol")).toString();
	if (proto == QStringLiteral("mjpeg"))
		return QStringLiteral("http://%1:%2/video").arg(ip).arg(s.value("mjpegPort").toInt(4747));
	if (proto == QStringLiteral("rtsp"))
		return QStringLiteral("rtsp://%1:%2/video").arg(ip).arg(s.value("rtspPort").toInt(5540));
	if (proto == QStringLiteral("srt")) {
		const int port = s.value("srtPort").toInt(9710);
		// Phone listener → OBS dials in (caller). Phone caller → OBS listens.
		if (s.value("srtMode").toString() == QStringLiteral("caller"))
			return QStringLiteral("srt://0.0.0.0:%1?mode=listener&latency=200").arg(port);
		return QStringLiteral("srt://%1:%2?mode=caller&latency=200").arg(ip).arg(port);
	}
	if (proto == QStringLiteral("rist")) {
		const int port = s.value("ristPort").toInt(5004);
		// Phone listener → OBS calls it. Phone caller → OBS listens (set ristHost to this PC).
		if (s.value("ristMode").toString() == QStringLiteral("listener"))
			return QStringLiteral("rist://%1:%2").arg(ip).arg(port);
		return QStringLiteral("rist://@:%1").arg(port);
	}
	if (proto == QStringLiteral("webrtc")) {
		isBrowser = true;
		return QStringLiteral("http://%1:%2/webrtc/view").arg(ip).arg(s.value("webControlPort").toInt(8080));
	}
	return {};
}

void LenscastDock::addOrRefreshSource()
{
	bool isBrowser = false;
	const QString url = streamUrlFor(lastStatus_, isBrowser);
	if (url.isEmpty()) {
		note(tr("Connect first so I know the protocol and ports."), true);
		return;
	}

	static const char *kName = "Lenscast";
	const char *wantId = isBrowser ? "browser_source" : "ffmpeg_source";

	obs_source_t *existing = obs_get_source_by_name(kName);
	if (existing && qstrcmp(obs_source_get_id(existing), wantId) != 0) {
		// Protocol changed between a network stream and WebRTC — replace the wrong-type source.
		obs_source_remove(existing);
		obs_source_release(existing);
		existing = nullptr;
	}

	obs_data_t *settings = obs_data_create();
	if (isBrowser) {
		obs_data_set_string(settings, "url", url.toUtf8().constData());
		obs_data_set_int(settings, "width", 1280);
		obs_data_set_int(settings, "height", 720);
		obs_data_set_bool(settings, "reroute_audio", true);
	} else {
		obs_data_set_bool(settings, "is_local_file", false);
		obs_data_set_string(settings, "input", url.toUtf8().constData());
		obs_data_set_string(settings, "input_format", "");
		obs_data_set_int(settings, "reconnect_delay_sec", 2);
		obs_data_set_bool(settings, "restart_on_activate", false);
		obs_data_set_bool(settings, "hw_decode", true);
	}

	if (existing) {
		obs_source_update(existing, settings);
		obs_source_release(existing);
		note(tr("Refreshed source “%1” → %2").arg(QString::fromUtf8(kName), url));
	} else {
		obs_source_t *src = obs_source_create(wantId, kName, settings, nullptr);
		obs_source_t *scene_src = obs_frontend_get_current_scene();
		obs_scene_t *scene = obs_scene_from_source(scene_src);
		if (scene && src)
			obs_scene_add(scene, src);
		obs_source_release(scene_src);
		obs_source_release(src);
		note(tr("Added source “%1” → %2").arg(QString::fromUtf8(kName), url));
	}
	obs_data_release(settings);

	if (lastStatus_.value(QStringLiteral("protocol")).toString() == QStringLiteral("rist"))
		note(noteLabel_->text() + tr("  (RIST needs OBS' bundled ffmpeg built with librist.)"));
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
