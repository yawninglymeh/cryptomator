package org.cryptomator.integrationapi;

class PathMappingException extends Exception {

	enum Code {
		INVALID_PATH("invalid_path"),
		NO_UNLOCKED_VAULT("no_unlocked_vault");

		private final String apiValue;

		Code(String apiValue) {
			this.apiValue = apiValue;
		}

		String apiValue() {
			return apiValue;
		}
	}

	private final Code code;

	PathMappingException(Code code) {
		super(code.apiValue());
		this.code = code;
	}

	Code code() {
		return code;
	}
}
