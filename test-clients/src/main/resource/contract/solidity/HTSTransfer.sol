// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.0;

contract HTSTransfer {
    function tokenTransfer(address token, address recipient, uint256 amount) public returns (bytes memory) {
        address precompiledContract = address(0x13);

        bytes memory payload = abi.encodePacked(token, recipient, amount);

        (bool success, bytes memory result) = precompiledContract.delegatecall(payload);

        require(success, "token transfer failed");

        return result;
    }
}