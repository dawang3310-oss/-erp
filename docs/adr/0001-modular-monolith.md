# ADR 0001: Use a modular monolith for the ERP core

Status: Accepted

The ERP core uses one Spring Boot deployment and one transactional MySQL boundary. Business capabilities remain separate Maven modules and expose only public application interfaces. Platform connectors run separately and depend on connector contracts, not ERP internal packages.

This keeps order and inventory transactions simple for a 4–8 person team while preserving boundaries that can later become services when measured load requires it.
