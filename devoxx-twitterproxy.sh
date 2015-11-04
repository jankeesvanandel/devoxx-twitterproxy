#!/bin/sh
### BEGIN INIT INFO
# Provides:          devoxx-twitterproxy
# Required-Start:    $local_fs $network $named $time $syslog
# Required-Stop:     $local_fs $network $named $time $syslog
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Description:       devoxx-twitterproxy
### END INIT INFO

SCRIPT=/opt/devoxx-twitterproxy/devoxx-twitterproxy-1.0/bin/devoxx-twitterproxy
RUNAS=ec2-user

PIDFILE=/var/run/devoxx-twitterproxy.pid
LOGFILE=/var/log/devoxx-twitterproxy.log

start() {
  if [ -f /var/run/$PIDNAME ] && kill -0 $(cat /var/run/$PIDNAME); then
    echo 'Service already running' >&2
    return 1
  fi
  echo 'Starting service...' >&2
  source ~/.twitter4j.props
  OPTS="-Dtwitter4j.oauth.consumerKey=$twitter4j_oauth_consumerKey -Dtwitter4j.oauth.consumerSecret=$twitter4j_oauth_consumerSecret -Dtwitter4j.oauth.accessToken=$twitter4j_oauth_accessToken -Dtwitter4j.oauth.accessTokenSecret=$twitter4j_oauth_accessTokenSecret"
  local CMD="$SCRIPT $OPTS &> \"$LOGFILE\" & echo \$!"
  su -c "$CMD" $RUNAS > "$PIDFILE"
  echo 'Service started' >&2
}
stop() {
  if [ ! -f "$PIDFILE" ] || ! kill -0 $(cat "$PIDFILE"); then
    echo 'Service not running' >&2
    return 1
  fi
  echo 'Stopping service...' >&2
  kill -15 $(cat "$PIDFILE") && rm -f "$PIDFILE"
  echo 'Service stopped' >&2
}

uninstall() {
  echo -n "Are you really sure you want to uninstall this service? That cannot be undone. [yes|No] "
  local SURE
  read SURE
  if [ "$SURE" = "yes" ]; then
    stop
    rm -f "$PIDFILE"
    echo "Notice: log file is not be removed: '$LOGFILE'" >&2
    update-rc.d -f devoxx-twitterproxy remove
    rm -fv "$0"
  fi
}

case "$1" in
  start)
    start
    ;;
  stop)
    stop
    ;;
  uninstall)
    uninstall
    ;;
  restart)
    stop
    start
    ;;
  *)
    echo "Usage: $0 {start|stop|restart|uninstall}"
esac
