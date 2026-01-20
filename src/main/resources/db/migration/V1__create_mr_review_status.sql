create table if not exists mr_review_status (
                                                id bigserial primary key,
                                                project_id int not null,
                                                mr_iid int not null,
                                                head_sha text not null,
                                                status text not null,
                                                attempts int not null default 0,
                                                last_error text,
                                                created_at timestamptz not null,
                                                updated_at timestamptz not null,
                                                started_at timestamptz,
                                                finished_at timestamptz,
                                                version bigint not null default 0,
                                                constraint uk_mr_review_status_project_mr unique (project_id, mr_iid)
    );
