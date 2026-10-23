-- Preserve real pending requests, including the current revision of a re-request.
-- Resolve historical accepted/rejected/cancelled requests without changing other alerts.
update notifications n
set read_at = current_timestamp
where n.notification_type = 'FRIEND_REQUEST'
  and n.read_at is null
  and n.event_key like 'friendship:%:requested:%'
  and not exists (
      select 1 from friendships f
      join app_users requester on requester.id = f.requester_id
      join app_users receiver on receiver.id = f.receiver_id
      where concat('friendship:', cast(f.id as varchar), ':requested:',
                   cast(f.request_revision as varchar)) = n.event_key
        and f.receiver_id = n.user_id and f.status = 'PENDING'
        and requester.deleted_at is null and receiver.deleted_at is null
  );
