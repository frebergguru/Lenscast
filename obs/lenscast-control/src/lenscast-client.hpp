#pragma once

#include <QObject>
#include <QString>
#include <QJsonObject>
#include <QNetworkAccessManager>

// Thin async wrapper over the Lenscast REST API (Docs/API.md). Bearer-token auth, runs on the
// Qt UI thread via QNetworkAccessManager, and reports results through signals so the dock never
// blocks. A self-signed HTTPS endpoint is accepted (LAN use); see configure().
class LenscastClient : public QObject {
	Q_OBJECT
public:
	explicit LenscastClient(QObject *parent = nullptr);

	void configure(const QString &host, int port, const QString &token, bool https);
	bool configured() const { return !host_.isEmpty() && !token_.isEmpty(); }
	QString host() const { return host_; }
	int port() const { return port_; }

	void fetchStatus();                       // -> statusReady / requestFailed
	void post(const QString &path,
		  const QJsonObject &body = {});  // -> actionDone(path, ok, message)

signals:
	void statusReady(const QJsonObject &status);
	void requestFailed(const QString &what);
	void actionDone(const QString &path, bool ok, const QString &message);

private:
	QNetworkRequest request(const QString &path) const;
	QString base() const;

	QNetworkAccessManager nam_;
	QString host_;
	int port_ = 8088;
	QString token_;
	bool https_ = false;
};
