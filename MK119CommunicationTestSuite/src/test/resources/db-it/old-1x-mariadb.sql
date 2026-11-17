-- 1.x 가 MariaDB 에 만들던 스키마(MariaDBDialect, utf8mb4). SQL Server 판과 같은 열·제약이다.
-- 문장은 줄 끝의 ; 로 나눈다.
CREATE TABLE groups (
    id int NOT NULL AUTO_INCREMENT,
    name varchar(255) NULL,
    parent_id int NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;
CREATE TABLE devices (
    id int NOT NULL AUTO_INCREMENT,
    communication_settings longtext NULL,
    enabled int NULL,
    host varchar(255) NULL,
    log int NULL,
    name varchar(255) NULL,
    port int NULL,
    protocol int NULL,
    transport int NULL,
    group_id int NULL,
    PRIMARY KEY (id),
    CONSTRAINT UK_devices_name UNIQUE (name),
    CONSTRAINT FKrk2sihswn5kajparaih5i35g4 FOREIGN KEY (group_id) REFERENCES groups (id)
) ENGINE=InnoDB;
CREATE TABLE simulations (
    id int NOT NULL AUTO_INCREMENT,
    enabled int NOT NULL,
    `interval` int NULL,
    name varchar(255) NULL,
    policies longtext NULL,
    stop_on_control int NOT NULL,
    target longtext NULL,
    device_id int NULL,
    PRIMARY KEY (id),
    CONSTRAINT UKqlv6jjlfciddgieev5nqfdp12 UNIQUE (name, device_id),
    CONSTRAINT FKmfy8rnwsdsed7da66fhe1qybw FOREIGN KEY (device_id) REFERENCES devices (id)
) ENGINE=InnoDB;
CREATE TABLE errors (
    id int NOT NULL AUTO_INCREMENT,
    enabled int NOT NULL,
    error_duration int NULL,
    error_value double NULL,
    frequency int NOT NULL,
    garbage_length int NOT NULL,
    `interval` int NULL,
    mode int NULL,
    simulation_stop int NULL,
    timeout int NOT NULL,
    device_id int NULL,
    simulation_id int NULL,
    PRIMARY KEY (id),
    CONSTRAINT FK41uatlpck2xxfs7asd2ann9pk FOREIGN KEY (device_id) REFERENCES devices (id),
    CONSTRAINT FKdxlfyt6sf5e2ba4pgu3xj6u55 FOREIGN KEY (simulation_id) REFERENCES simulations (id)
) ENGINE=InnoDB;
