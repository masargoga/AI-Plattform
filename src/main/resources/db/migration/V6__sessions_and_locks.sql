-- Persistent sign-in tokens (Spring Security "remember me"): lets any replica recognise a signed-in user after a failover
CREATE TABLE persistent_logins (
    username  VARCHAR(64) NOT NULL,
    series    VARCHAR(64) NOT NULL PRIMARY KEY,
    token     VARCHAR(64) NOT NULL,
    last_used TIMESTAMP   NOT NULL
);
CREATE INDEX ix_persistent_logins_user ON persistent_logins (username);

-- Database lock so that background jobs run on one replica at a time
CREATE TABLE job_lock (
    name         VARCHAR(64)  NOT NULL PRIMARY KEY,
    locked_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by    VARCHAR(128) NOT NULL
);
