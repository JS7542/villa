FROM nginx:1.28-alpine
RUN rm /etc/nginx/conf.d/default.conf \
    && rm -f /var/log/nginx/access.log /var/log/nginx/error.log
COPY nginx.conf /etc/nginx/nginx.conf
COPY conf.d/villa.conf /etc/nginx/conf.d/villa.conf
