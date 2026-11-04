FROM mysql:8.4.9
COPY conf.d/villa.cnf /etc/mysql/conf.d/villa.cnf
COPY init/ /docker-entrypoint-initdb.d/
COPY entrypoint.sh /usr/local/bin/villa-mysql-entrypoint
RUN chmod 644 /etc/mysql/conf.d/villa.cnf && chmod +x /usr/local/bin/villa-mysql-entrypoint
ENTRYPOINT ["/usr/local/bin/villa-mysql-entrypoint"]
CMD ["mysqld"]
