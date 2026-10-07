-- Demo data for Aurora Grand Chicago. Re-applied at startup (guestops.hotel.reset-demo-data=true)
-- so that dates are always relative to today.
TRUNCATE operation_log, folio_charges, maintenance_tickets, housekeeping_tasks, reservations, rooms, guests
    RESTART IDENTITY CASCADE;

INSERT INTO guests VALUES
('G-1001', 'Priya',  'Raman',    'priya.raman@example.com',   '312-555-0141', 'AU88231001', 'PLATINUM', 214, 'High floor, extra pillows, sparkling water', 'Celebrating anniversary this stay'),
('G-1002', 'Marcus', 'Lee',      'marcus.lee@example.com',    '415-555-0172', 'AU88231002', 'GOLD',      61, 'Late checkout when possible, quiet room', NULL),
('G-1003', 'Elena',  'Petrova',  'elena.petrova@example.com', '646-555-0110', 'AU88231003', 'MEMBER',     3, 'Early check-in requested', 'Arriving on red-eye flight'),
('G-1004', 'James',  'Okafor',   'james.okafor@example.com',  '773-555-0199', 'AU88231004', 'SILVER',    18, 'Accessible room, roll-in shower', 'Uses a wheelchair'),
('G-1005', 'Sofia',  'Martinez', 'sofia.m@example.com',       '305-555-0133', 'AU88231005', 'PLATINUM', 342, 'Suite upgrades, Lake view', 'VIP: regional sales director at a key corporate account'),
('G-1006', 'Daniel', 'Kim',      'daniel.kim@example.com',    '206-555-0188', 'AU88231006', 'MEMBER',     1, NULL, NULL),
('G-1007', 'Aisha',  'Bello',    'aisha.bello@example.com',   '404-555-0122', 'AU88231007', 'GOLD',      47, 'Feather-free bedding (allergy)', 'Travelling with a small dog'),
('G-1008', 'Tom',    'Whitaker', 'tom.w@example.com',         '617-555-0164', 'AU88231008', 'SILVER',    25, 'Two queens, rollaway for child', 'Family of four');

INSERT INTO rooms VALUES
('702',  7, 'KING', 'Deluxe King',        FALSE, 'City',  'OCCUPIED',     'Walk-in shower'),
('714',  7, 'KING', 'Accessible King',    TRUE,  'City',  'OCCUPIED',     'Roll-in shower, lowered fixtures, visual alarms'),
('716',  7, 'KING', 'Accessible King',    TRUE,  'City',  'VACANT_CLEAN', 'Roll-in shower, lowered fixtures, visual alarms'),
('808',  8, 'QQ',   'Deluxe Two Queens',  FALSE, 'City',  'VACANT_CLEAN', 'Sofa bed'),
('810',  8, 'QQ',   'Deluxe Two Queens',  FALSE, 'City',  'OCCUPIED',     'Sofa bed'),
('902',  9, 'KING', 'Deluxe King',        FALSE, 'Lake',  'OUT_OF_ORDER', 'Bathtub'),
('905',  9, 'QQ',   'Deluxe Two Queens',  FALSE, 'Lake',  'OCCUPIED',     'Bathtub'),
('912',  9, 'KING', 'Deluxe King',        FALSE, 'Lake',  'VACANT_CLEAN', 'Bathtub, pet-friendly floor'),
('1012', 10, 'KING', 'Deluxe King',       FALSE, 'City',  'VACANT_DIRTY', 'Walk-in shower'),
('1014', 10, 'KING', 'Deluxe King',       FALSE, 'City',  'VACANT_CLEAN', 'Walk-in shower'),
('1104', 11, 'JRS',  'Junior Suite',      FALSE, 'Lake',  'VACANT_CLEAN', 'Separate living area, soaking tub'),
('1106', 11, 'JRS',  'Junior Suite',      FALSE, 'Lake',  'OCCUPIED',     'Separate living area, soaking tub'),
('1208', 12, 'KING', 'Deluxe King',       FALSE, 'Lake',  'OCCUPIED',     'Bathtub'),
('1210', 12, 'KING', 'Deluxe King',       FALSE, 'Lake',  'VACANT_CLEAN', 'Bathtub'),
('1215', 12, 'KING', 'Deluxe King',       FALSE, 'City',  'VACANT_DIRTY', 'Walk-in shower'),
('1302', 13, 'KING', 'Deluxe King',       FALSE, 'Lake',  'VACANT_CLEAN', 'Bathtub'),
('1401', 14, 'EXS',  'Executive Suite',   FALSE, 'Lake',  'VACANT_CLEAN', 'Dining table, two bathrooms, lounge access'),
('1402', 14, 'EXS',  'Executive Suite',   FALSE, 'Lake',  'OCCUPIED',     'Dining table, two bathrooms, lounge access');

INSERT INTO reservations VALUES
('AUR-10021', 'G-1001', '1208', 'KING', CURRENT_DATE - 2, CURRENT_DATE + 1, '12:00', 'IN_HOUSE', 289.00, 'BAR',             2, 'Anniversary amenity', NULL),
('AUR-10034', 'G-1002', '905',  'QQ',   CURRENT_DATE - 3, CURRENT_DATE,     '12:00', 'IN_HOUSE', 249.00, 'CORPORATE-ACME',  1, 'Requests 2pm late checkout', NULL),
('AUR-10045', 'G-1003', '1012', 'KING', CURRENT_DATE,     CURRENT_DATE + 2, '12:00', 'RESERVED', 269.00, 'ADVANCE-PURCHASE',1, 'Early arrival, ~9am', '09:00'),
('AUR-10052', 'G-1004', '714',  'KING', CURRENT_DATE - 1, CURRENT_DATE + 3, '12:00', 'IN_HOUSE', 259.00, 'BAR',             1, 'Accessible room required', NULL),
('AUR-10060', 'G-1005', '1302', 'KING', CURRENT_DATE,     CURRENT_DATE + 3, '12:00', 'RESERVED', 319.00, 'CORPORATE-GLOBEX',1, 'Suite upgrade if available', '15:00'),
('AUR-10071', 'G-1006', '810',  'QQ',   CURRENT_DATE - 1, CURRENT_DATE + 1, '12:00', 'IN_HOUSE', 229.00, 'OTA-PREPAID',     2, NULL, NULL),
('AUR-10083', 'G-1007', '702',  'KING', CURRENT_DATE - 1, CURRENT_DATE + 2, '12:00', 'IN_HOUSE', 279.00, 'BAR',             1, 'Pet: small dog under 25 lb', NULL),
('AUR-10094', 'G-1008', NULL,   'QQ',   CURRENT_DATE + 1, CURRENT_DATE + 4, '12:00', 'RESERVED', 239.00, 'FAMILY-PACKAGE',  2, 'Rollaway bed', NULL);

INSERT INTO housekeeping_tasks (room_number, task_type, status, priority, assigned_to, eta_minutes, notes) VALUES
('1012', 'DEPARTURE_CLEAN', 'IN_PROGRESS', 'NORMAL', 'Rosa M.',  35, 'Early arrival AUR-10045 assigned'),
('1215', 'DEPARTURE_CLEAN', 'QUEUED',      'NORMAL', NULL,       90, NULL),
('905',  'STAYOVER',        'DONE',        'NORMAL', 'Ken T.',   NULL, 'Guest departing today'),
('1208', 'TURNDOWN',        'QUEUED',      'NORMAL', 'Ana P.',   NULL, 'Anniversary amenity to place');

INSERT INTO maintenance_tickets (room_number, category, issue, priority, status, assigned_to) VALUES
('902',  'PLUMBING', 'Bathtub drain leaking into ceiling below; room out of order', 'HIGH',   'IN_PROGRESS', 'Engineering - Luis'),
('714',  'NOISE',    'Guest reported loud bass from function room on level 6 after 11pm', 'NORMAL', 'OPEN', NULL);

INSERT INTO folio_charges (confirmation_number, charge_date, category, description, amount) VALUES
('AUR-10021', CURRENT_DATE - 2, 'ROOM',    'Room charge - BAR',            289.00),
('AUR-10021', CURRENT_DATE - 2, 'TAX',     'Room tax 17.4%',                50.29),
('AUR-10021', CURRENT_DATE - 1, 'ROOM',    'Room charge - BAR',            289.00),
('AUR-10021', CURRENT_DATE - 1, 'TAX',     'Room tax 17.4%',                50.29),
('AUR-10021', CURRENT_DATE - 1, 'DINING',  'Lakeside Grill - dinner',      142.60),
('AUR-10034', CURRENT_DATE - 3, 'ROOM',    'Room charge - CORPORATE-ACME', 249.00),
('AUR-10034', CURRENT_DATE - 3, 'TAX',     'Room tax 17.4%',                43.33),
('AUR-10034', CURRENT_DATE - 2, 'ROOM',    'Room charge - CORPORATE-ACME', 249.00),
('AUR-10034', CURRENT_DATE - 2, 'TAX',     'Room tax 17.4%',                43.33),
('AUR-10034', CURRENT_DATE - 2, 'MINIBAR', 'Minibar - spirits and snacks',  48.00),
('AUR-10034', CURRENT_DATE - 1, 'ROOM',    'Room charge - CORPORATE-ACME', 249.00),
('AUR-10034', CURRENT_DATE - 1, 'TAX',     'Room tax 17.4%',                43.33),
('AUR-10034', CURRENT_DATE - 1, 'PARKING', 'Valet parking',                 65.00),
('AUR-10052', CURRENT_DATE - 1, 'ROOM',    'Room charge - BAR',            259.00),
('AUR-10052', CURRENT_DATE - 1, 'TAX',     'Room tax 17.4%',                45.07),
('AUR-10083', CURRENT_DATE - 1, 'ROOM',    'Room charge - BAR',            279.00),
('AUR-10083', CURRENT_DATE - 1, 'TAX',     'Room tax 17.4%',                48.55),
('AUR-10083', CURRENT_DATE - 1, 'OTHER',   'Pet fee (per stay)',            75.00);
