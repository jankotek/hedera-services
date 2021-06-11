pragma solidity ^0.4.24;

contract SimpleStorage {
    uint storedData;
    uint secondStoredData;

    function set(uint x) public {
        storedData = x;
        secondStoredData = x;
    }

    function get() public view returns (uint) {
        return storedData;
    }

    function getSecond() public view returns (uint) {
        return secondStoredData;
    }
}