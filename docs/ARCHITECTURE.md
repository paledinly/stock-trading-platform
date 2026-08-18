# Architecture

The initial deployment is a domain-oriented Spring Boot modular monolith. React and Flutter call only the backend; KIS credentials remain server-side. PostgreSQL is the system of record and Redis stores ephemeral realtime state.

