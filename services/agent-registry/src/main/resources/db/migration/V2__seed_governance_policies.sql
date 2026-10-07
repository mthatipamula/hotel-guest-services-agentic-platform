-- One policy per agent (20 agents). Tool lists are allowlists: an agent never sees other tools.
-- High-impact MCP tools (moveGuestToRoom, applyFolioCredit, grantLateCheckout) are in NO agent's list:
-- they run only after a human approves an action proposed through proposeAction.
INSERT INTO governance_policies
    (agent_id, allowed_tools, allowed_callers, max_output_tokens, max_tokens_per_request,
     daily_token_budget, requires_human_approval, data_classification) VALUES
-- guest-ops-orchestrator (core network, in-process)
('triage-router',          '{}', '{guest-ops-orchestrator}', 1024, 6000,  3000000, FALSE, 'CONFIDENTIAL_PII'),
('safety-guard',           '{}', '{guest-ops-orchestrator}', 512,  3000,  3000000, FALSE, 'CONFIDENTIAL_PII'),
('reservation-agent',      '{getReservation,findReservationsByGuestName,getGuestProfile,searchHotelPolicies,proposeAction}',
                           '{guest-ops-orchestrator}', 2048, 20000, 2000000, TRUE,  'CONFIDENTIAL_PII'),
('guest-profile-agent',    '{getGuestProfile,getReservation,findReservationsByGuestName,searchHotelPolicies}',
                           '{guest-ops-orchestrator}', 2048, 15000, 2000000, FALSE, 'CONFIDENTIAL_PII'),
('room-assignment-agent',  '{getReservation,getRoomStatus,findAvailableRooms,getHousekeepingStatus,getMaintenanceTickets,searchHotelPolicies,proposeAction}',
                           '{guest-ops-orchestrator}', 2048, 25000, 2000000, TRUE,  'CONFIDENTIAL_PII'),
('housekeeping-agent',     '{getHousekeepingStatus,getRoomStatus,requestHousekeeping,searchHotelPolicies}',
                           '{guest-ops-orchestrator}', 2048, 15000, 2000000, FALSE, 'INTERNAL'),
('maintenance-agent',      '{getMaintenanceTickets,createMaintenanceTicket,getRoomStatus,searchHotelPolicies}',
                           '{guest-ops-orchestrator}', 2048, 15000, 2000000, FALSE, 'INTERNAL'),
('billing-agent',          '{getFolio,getReservation,searchHotelPolicies,proposeAction}',
                           '{guest-ops-orchestrator}', 2048, 20000, 2000000, TRUE,  'CONFIDENTIAL_PII'),
('service-recovery-agent', '{getReservation,getGuestProfile,getFolio,searchHotelPolicies,proposeAction}',
                           '{guest-ops-orchestrator}', 2048, 25000, 2000000, TRUE,  'CONFIDENTIAL_PII'),
('policy-advisor-agent',   '{searchHotelPolicies}', '{guest-ops-orchestrator}', 2048, 15000, 2000000, FALSE, 'INTERNAL'),
('dining-agent',           '{getDiningOutlets,searchHotelPolicies}', '{guest-ops-orchestrator}', 2048, 12000, 2000000, FALSE, 'INTERNAL'),
('response-composer',      '{}', '{guest-ops-orchestrator}', 2048, 12000, 3000000, FALSE, 'CONFIDENTIAL_PII'),
-- revenue-agents (revenue network, A2A)
('pricing-agent',          '{getRateQuote,getOccupancyForecast}', '{guest-ops-orchestrator}', 2048, 15000, 1000000, FALSE, 'INTERNAL'),
('upsell-agent',           '{getUpgradeOffers,findAvailableRooms,getRateQuote}', '{guest-ops-orchestrator}', 2048, 15000, 1000000, FALSE, 'INTERNAL'),
('demand-forecast-agent',  '{getOccupancyForecast}', '{guest-ops-orchestrator}', 2048, 12000, 1000000, FALSE, 'INTERNAL'),
('group-sales-agent',      '{checkGroupAvailability,getRateQuote,searchHotelPolicies}', '{guest-ops-orchestrator}', 2048, 15000, 1000000, FALSE, 'INTERNAL'),
-- partner-agents (external partner network, A2A, no guest identity data)
('ground-transport-agent', '{quoteGroundTransport,holdGroundTransport}', '{guest-ops-orchestrator}', 1536, 12000, 500000, FALSE, 'PARTNER_SAFE'),
('local-experiences-agent','{searchLocalExperiences}', '{guest-ops-orchestrator}', 1536, 12000, 500000, FALSE, 'PARTNER_SAFE'),
('spa-wellness-agent',     '{getSpaAvailability,holdSpaAppointment}', '{guest-ops-orchestrator}', 1536, 12000, 500000, FALSE, 'PARTNER_SAFE'),
-- eval-runner (evaluation zone)
('quality-judge-agent',    '{}', '{eval-runner}', 4096, 30000, 5000000, FALSE, 'INTERNAL');
