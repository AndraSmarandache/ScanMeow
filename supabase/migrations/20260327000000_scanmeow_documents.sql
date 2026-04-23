-- ScanMeow: documents metadata + private storage bucket (run in Supabase SQL editor or via CLI).

create table if not exists public.documents (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  file_name text not null,
  storage_path text not null,
  created_at_millis bigint not null,
  created_at timestamptz not null default now()
);

create index if not exists documents_user_created on public.documents (user_id, created_at_millis desc);

alter table public.documents enable row level security;

create policy "documents_select_own"
  on public.documents for select
  using (auth.uid() = user_id);

create policy "documents_insert_own"
  on public.documents for insert
  with check (auth.uid() = user_id);

create policy "documents_update_own"
  on public.documents for update
  using (auth.uid() = user_id);

create policy "documents_delete_own"
  on public.documents for delete
  using (auth.uid() = user_id);

-- Private bucket: object path must be "{user_uuid}/filename.pdf"
insert into storage.buckets (id, name, public)
values ('scans', 'scans', false)
on conflict (id) do nothing;

create policy "scans_select_own"
  on storage.objects for select
  using (bucket_id = 'scans' and (storage.foldername (name))[1] = (auth.uid())::text);

create policy "scans_insert_own"
  on storage.objects for insert
  with check (bucket_id = 'scans' and (storage.foldername (name))[1] = (auth.uid())::text);

create policy "scans_update_own"
  on storage.objects for update
  using (bucket_id = 'scans' and (storage.foldername (name))[1] = (auth.uid())::text);

create policy "scans_delete_own"
  on storage.objects for delete
  using (bucket_id = 'scans' and (storage.foldername (name))[1] = (auth.uid())::text);
