#!/bin/sh
set -e
echo "$1"
if [ $1 == "custom" ]
   java -cp /app/lib/*: -Dstore.adapter=postgresql -Dplugin.user.enabled=true -Devent-explorer.enabled=true -Dcustom-data-source.enabled=true -Duser.funnel-analysis.enabled=true -Dplugin.user.enable-user-mapping=true -Duser.retention-analysis.enabled=true -Dplugin.geoip.enabled=true -Dplugin.user.storage=postgresql -Dhttp.server.address=0.0.0.0:9999 -Dplugin.user.storage.identifier-column=id -Dplugin.geoip.database.url=file://tmp/GeoLite2-City.mmdb org.rakam.ServiceStarter
then
   java -cp /app/lib/*: -Dstore.adapter=postgresql -Dplugin.user.enabled=true -Devent-explorer.enabled=true -Dcustom-data-source.enabled=true -Duser.funnel-analysis.enabled=true -Dplugin.user.enable-user=true org.rakam.ServiceStarter
fi
