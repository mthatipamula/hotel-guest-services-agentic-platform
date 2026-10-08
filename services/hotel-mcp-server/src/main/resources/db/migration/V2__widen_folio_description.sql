-- Credit descriptions include the approver's justification, which can exceed 200 characters.
ALTER TABLE folio_charges ALTER COLUMN description TYPE VARCHAR(500);
