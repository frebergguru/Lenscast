#pragma once

#include <QFrame>
#include <QJsonObject>

class QLineEdit;
class QLabel;
class QPushButton;
class QToolButton;
class QComboBox;
class QTimer;
class LenscastClient;

// The OBS dock: connection, live status, camera quick-controls (driven through the REST API),
// and a one-click "add/refresh source" that points OBS at the phone's current protocol — an
// ffmpeg Media Source for MJPEG/RTSP/SRT/RIST, or a Browser Source on /webrtc/view for WebRTC.
// Public hk* methods are the entry points for the registered frontend hotkeys.
class LenscastDock : public QFrame {
	Q_OBJECT
public:
	explicit LenscastDock(QWidget *parent = nullptr);
	~LenscastDock() override;

	QSize sizeHint() const override { return QSize(380, 700); }

	// Called from frontend hotkey callbacks (UI thread).
	void hkStartStop();
	void hkToggleLens();
	void hkToggleTorch();
	void hkSnapshot();

private slots:
	void onConnectClicked();
	void onStatus(const QJsonObject &s);
	void onFailed(const QString &what);
	void onActionDone(const QString &path, bool ok, const QString &msg);
	void addOrRefreshSource();
	void poll();

private:
	void buildUi();
	void loadConfig();
	void saveConfig();
	void applyConfigToClient();
	QString streamUrlFor(const QJsonObject &s, bool &isBrowser) const;
	void note(const QString &line, bool error = false);

	LenscastClient *client_;
	QTimer *timer_;

	// Connection
	QLineEdit *hostEdit_;
	QLineEdit *portEdit_;
	QLineEdit *tokenEdit_;
	QLabel *statusDot_;
	QLabel *statusLabel_;
	QLabel *noteLabel_;

	// Stream
	QPushButton *startBtn_;
	QPushButton *stopBtn_;
	QComboBox *protocolBox_;

	// Camera quick-controls
	QToolButton *lensBtn_;
	QToolButton *torchBtn_;
	QToolButton *mirrorBtn_;
	QToolButton *afBtn_;
	QToolButton *zoomInBtn_;
	QToolButton *zoomOutBtn_;
	QToolButton *evUpBtn_;
	QToolButton *evDownBtn_;
	QToolButton *snapBtn_;
	QComboBox *resolutionBox_;
	QComboBox *fpsBox_;

	// Source
	QPushButton *sourceBtn_;

	QJsonObject lastStatus_;
	bool streaming_ = false;
};
