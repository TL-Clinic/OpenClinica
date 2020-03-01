# Dockerfile for OpenClinica
#
# - for testing purposes only
# - needs an additional postgres container

FROM tomcat:7

MAINTAINER Teresa Leonardo Clinic (webmaster@teresaleonardoclinic.com)

ENV  OC_HOME              $CATALINA_HOME/webapps/OpenClinica
ENV  OC_WS_HOME           $CATALINA_HOME/webapps/OpenClinica-ws

ENV  OC_VERSION           3.15

COPY run.sh               /run.sh

RUN  mkdir /tmp/oc && \
     cd /tmp/oc && \
     wget -q --no-check-certificate -Oopenclinica.zip https://distros.openclinica.com/OpenClinica-3.15.zip && \
     wget -q --no-check-certificate -Oopenclinica-ws.zip https://distros.openclinica.com/OpenClinica-ws-3.15.zip && \
     unzip openclinica.zip && \
     unzip openclinica-ws.zip && \
#### Remove default webapps
     rm -rf $CATALINA_HOME/webapps/* && \
     mkdir $OC_HOME && cd $OC_HOME && \
     cp /tmp/oc/OpenClinica-$OC_VERSION/distribution/OpenClinica.war . && \
     unzip OpenClinica.war && cd .. && \
     mkdir $OC_WS_HOME && cd $OC_WS_HOME && \
     cp /tmp/oc/OpenClinica-ws-$OC_VERSION/distribution/OpenClinica-ws.war . && \
     unzip OpenClinica-ws.war && cd .. && \
     rm -rf /tmp/oc && \
     mkdir $CATALINA_HOME/openclinica.data/xslt -p && \
     chmod +x /*.sh

ENV  JAVA_OPTS -Xmx1280m -XX:+UseParallelGC -XX:+CMSClassUnloadingEnabled

CMD  ["/run.sh"]
