#pragma once

#include <QFrame>
#include <QJsonObject>

class QLineEdit;
class QLabel;
class QPushButton;
class QComboBox;
class QTimer;
class LenscastClient;

// The OBS dock: connection fields, live status, camera controls (all driven through the REST
// API), and a one-click "add/refresh Media Source" that points OBS at the phone's stream URL.
// Public hk* methods are the entry points for the registered frontend hotkeys.
class LenscastDock : public QFrame {
	Q_OBJECT
public:
	explicit LenscastDock(QWidget *parent = nullptr);
	~LenscastDock() override;

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
	void addOrRefreshMediaSource();
	void poll();

private:
	void buildUi();
	void loadConfig();
	void saveConfig();
	void applyConfigToClient();
	QString streamUrlFor(const QJsonObject &s) const;
	void note(const QString &line);

	LenscastClient *client_;
	QTimer *timer_;

	QLineEdit *hostEdit_;
	QLineEdit *portEdit_;
	QLineEdit *tokenEdit_;
	QLabel *statusLabel_;
	QLabel *noteLabel_;
	QPushButton *startBtn_;
	QPushButton *stopBtn_;
	QPushButton *lensBtn_;
	QPushButton *torchBtn_;
	QPushButton *snapBtn_;
	QPushButton *zoomInBtn_;
	QPushButton *zoomOutBtn_;
	QPushButton *sourceBtn_;
	QComboBox *resolutionBox_;

	QJsonObject lastStatus_;
	bool streaming_ = false;
};
