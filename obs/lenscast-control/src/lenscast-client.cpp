#include "lenscast-client.hpp"

#include <QNetworkReply>
#include <QJsonDocument>
#include <QUrl>
#include <QSslError>

LenscastClient::LenscastClient(QObject *parent) : QObject(parent) {}

void LenscastClient::configure(const QString &host, int port, const QString &token, bool https)
{
	host_ = host.trimmed();
	port_ = port;
	token_ = token.trimmed();
	https_ = https;
}

QString LenscastClient::base() const
{
	return QStringLiteral("%1://%2:%3/api/v1")
		.arg(https_ ? QStringLiteral("https") : QStringLiteral("http"), host_)
		.arg(port_);
}

QNetworkRequest LenscastClient::request(const QString &path) const
{
	QNetworkRequest req{QUrl(base() + path)};
	req.setRawHeader("Authorization", QByteArray("Bearer ") + token_.toUtf8());
	req.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
	return req;
}

void LenscastClient::fetchStatus()
{
	if (!configured()) {
		emit requestFailed(tr("Set the phone IP and API token first"));
		return;
	}
	QNetworkReply *reply = nam_.get(request(QStringLiteral("/status")));
	if (https_)
		connect(reply, &QNetworkReply::sslErrors, reply,
			qOverload<const QList<QSslError> &>(&QNetworkReply::ignoreSslErrors));
	connect(reply, &QNetworkReply::finished, this, [this, reply]() {
		reply->deleteLater();
		if (reply->error() != QNetworkReply::NoError) {
			emit requestFailed(reply->errorString());
			return;
		}
		const QJsonDocument doc = QJsonDocument::fromJson(reply->readAll());
		if (!doc.isObject()) {
			emit requestFailed(tr("Unexpected /status response"));
			return;
		}
		emit statusReady(doc.object());
	});
}

void LenscastClient::post(const QString &path, const QJsonObject &body)
{
	if (!configured()) {
		emit requestFailed(tr("Set the phone IP and API token first"));
		return;
	}
	const QByteArray data =
		body.isEmpty() ? QByteArray() : QJsonDocument(body).toJson(QJsonDocument::Compact);
	QNetworkReply *reply = nam_.post(request(path), data);
	if (https_)
		connect(reply, &QNetworkReply::sslErrors, reply,
			qOverload<const QList<QSslError> &>(&QNetworkReply::ignoreSslErrors));
	connect(reply, &QNetworkReply::finished, this, [this, reply, path]() {
		reply->deleteLater();
		const int code =
			reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
		const bool ok = reply->error() == QNetworkReply::NoError && code >= 200 && code < 300;
		const QString msg = ok ? QStringLiteral("ok")
				       : QStringLiteral("%1 (%2)").arg(reply->errorString()).arg(code);
		emit actionDone(path, ok, msg);
	});
}
