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

-- =========================
-- 2) Telegram bot: USERS
-- =========================
create table if not exists public.users (
                                            id bigserial primary key,
                                            chat_id bigint not null unique,
                                            telegram_username text,
                                            first_name text,
                                            current_level int not null default 1,
                                            max_unlocked_level int not null default 1,
                                            total_points int not null default 0,
                                            created_at timestamp not null default now(),
    last_activity_at timestamp not null default now()
    );

-- =========================
-- 3) Telegram bot: COMPLETED_TASKS
-- =========================
create table if not exists public.completed_tasks (
                                                      id bigserial primary key,
                                                      user_id bigint not null references public.users(id) on delete cascade,
    task_id text not null,
    task_name text not null,
    points int not null,
    completed_at timestamp not null default now(),
    constraint uk_completed_tasks_user_task unique (user_id, task_id)
    );

create index if not exists idx_completed_tasks_user_id
    on public.completed_tasks(user_id);

-- =========================
-- 4) Telegram bot: USER_SCORES
-- =========================
create table if not exists public.user_scores (
                                                  id bigserial primary key,
                                                  user_id bigint not null references public.users(id) on delete cascade,
    points int not null,
    source_type text,
    source_id text,
    created_at timestamp not null default now()
    );

create index if not exists idx_user_scores_user_id
    on public.user_scores(user_id);

create index if not exists idx_user_scores_created_at
    on public.user_scores(created_at);