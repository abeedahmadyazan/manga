# YZ MANGA API

Server-side protection layer for YZ MANGA Android app.

## Endpoints

### Public
- `GET /health` - Health check

### Comments (requires Firebase ID token in Authorization header)
- `GET /comments/:contextId` - Get comments for a context
- `POST /comments` - Add a comment (rate limited: 1/min/user)
- `PUT /comments/:id` - Edit a comment (owner only)
- `DELETE /comments/:id` - Delete a comment (owner or admin)
- `POST /comments/:id/react` - Like/dislike a comment

### Reports
- `POST /reports` - Submit a report

### Admin (requires admin role)
- `GET /admin/reports` - List unresolved reports
- `PUT /admin/reports/:id/resolve` - Resolve a report
- `POST /admin/ban` - Ban a user
- `DELETE /admin/ban/:email` - Unban a user

## Environment Variables

Required:
- `FIREBASE_PROJECT_ID` - Firebase project ID
- `FIREBASE_CLIENT_EMAIL` - Service account email
- `FIREBASE_PRIVATE_KEY` - Service account private key

## Protection Layers

1. **Auth verification** - Every request must have valid Firebase ID token
2. **Rate limiting** - 60 req/min per IP, 1 comment/min per user
3. **Spam filter** - Banned words, caps check, length validation
4. **Cooldown** - 60 seconds between comments
5. **Max per context** - 2 comments per chapter per user
6. **Owner checks** - Users can only edit/delete their own content
7. **Admin checks** - Admin operations require admin role
