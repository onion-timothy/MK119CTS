-- 1.x 가 SQL Server 에 만들던 스키마. 172.17.74.4 의 cts(2026-11-17) 를 그대로 옮겼다(제약 이름 포함).
-- 문자열 열이 varchar·text 다 — DB collation(Korean_Wansung) 의 코드 페이지에 묶여 있다.
-- 문장은 줄 끝의 ; 로 나눈다.
CREATE TABLE groups (
    id int IDENTITY(1,1) NOT NULL,
    name varchar(255) NULL,
    parent_id int NULL,
    CONSTRAINT PK_groups PRIMARY KEY (id)
);
CREATE TABLE devices (
    id int IDENTITY(1,1) NOT NULL,
    communication_settings text NULL,
    enabled int NULL,
    host varchar(255) NULL,
    log int NULL,
    name varchar(255) NULL,
    port int NULL,
    protocol int NULL,
    transport int NULL,
    group_id int NULL,
    CONSTRAINT PK_devices PRIMARY KEY (id),
    CONSTRAINT UK_devices_name UNIQUE (name),
    CONSTRAINT FKrk2sihswn5kajparaih5i35g4 FOREIGN KEY (group_id) REFERENCES groups (id)
);
CREATE TABLE simulations (
    id int IDENTITY(1,1) NOT NULL,
    enabled int NOT NULL,
    [interval] int NULL,
    name varchar(255) NULL,
    policies text NULL,
    stop_on_control int NOT NULL,
    target text NULL,
    device_id int NULL,
    CONSTRAINT PK_simulations PRIMARY KEY (id),
    CONSTRAINT UKqlv6jjlfciddgieev5nqfdp12 UNIQUE (name, device_id),
    CONSTRAINT FKmfy8rnwsdsed7da66fhe1qybw FOREIGN KEY (device_id) REFERENCES devices (id)
);
CREATE TABLE errors (
    id int IDENTITY(1,1) NOT NULL,
    enabled int NOT NULL,
    error_duration int NULL,
    error_value float NULL,
    frequency int NOT NULL,
    garbage_length int NOT NULL,
    [interval] int NULL,
    mode int NULL,
    simulation_stop int NULL,
    timeout int NOT NULL,
    device_id int NULL,
    simulation_id int NULL,
    CONSTRAINT PK_errors PRIMARY KEY (id),
    CONSTRAINT FK41uatlpck2xxfs7asd2ann9pk FOREIGN KEY (device_id) REFERENCES devices (id),
    CONSTRAINT FKdxlfyt6sf5e2ba4pgu3xj6u55 FOREIGN KEY (simulation_id) REFERENCES simulations (id)
);
